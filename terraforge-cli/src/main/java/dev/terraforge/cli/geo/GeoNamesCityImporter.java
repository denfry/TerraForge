package dev.terraforge.cli.geo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.locationtech.jts.geom.Envelope;

/** Imports the documented GeoNames tab-separated cities export; no geometry or world data is created. */
public final class GeoNamesCityImporter {

    /** Alternate names kept per city. Enough to cover the languages people actually type. */
    private static final int MAX_ALTERNATE_NAMES = 12;

    private GeoNamesCityImporter() { }

    public static int importFile(Path source, Connection connection) throws IOException, SQLException {
        return importFile(source, connection, null);
    }

    /**
     * @param clip optional lon/lat envelope; entries outside it are skipped instead of imported,
     *             which keeps a regional database from carrying the whole planet's gazetteer
     */
    public static int importFile(Path source, Connection connection, Envelope clip)
            throws IOException, SQLException {
        int imported = 0;
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO cities (name, ascii_name, latitude, longitude, population, country_id, capital)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            int lineNumber = 0;
            for (String line : Files.readAllLines(source)) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split("\\t", -1);
                if (fields.length < 15) throw new IOException("GeoNames line " + lineNumber + " has fewer than 15 columns");
                double latitude = parseCoordinate(fields[4], -90, 90, lineNumber);
                double longitude = parseCoordinate(fields[5], -180, 180, lineNumber);
                if (clip != null && !clip.contains(longitude, latitude)) continue;
                long population = parsePopulation(fields[14], lineNumber);
                insert.setString(1, required(fields[1], "name", lineNumber));
                insert.setString(2, blankToNull(fields[2]));
                insert.setDouble(3, latitude); insert.setDouble(4, longitude); insert.setLong(5, population);
                Integer countryId = countryId(connection, fields[8]);
                if (countryId == null) insert.setNull(6, java.sql.Types.INTEGER); else insert.setInt(6, countryId);
                insert.setInt(7, "PPLC".equals(fields[7]) ? 1 : 0);
                insert.executeUpdate(); imported++;
                insertAlternateNames(connection, insert, fields[3], fields[2]);
            }
        }
        return imported;
    }
    /**
     * Stores the ASCII form and GeoNames' comma-separated alternate names, so a player can find
     * "München" by typing "Munich" or "Muenchen".
     *
     * <p>Capped per city: GeoNames carries every localisation of a large city, which for a planet
     * -wide import would be several million rows nobody ever searches. The first entries are the
     * widely used ones.
     */
    private static void insertAlternateNames(Connection connection, PreparedStatement city,
                                             String alternateNames, String asciiName) throws SQLException {
        int cityId;
        try (ResultSet keys = city.getGeneratedKeys()) {
            if (!keys.next()) {
                return;
            }
            cityId = keys.getInt(1);
        }
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        String ascii = blankToNull(asciiName);
        if (ascii != null) {
            names.add(ascii);
        }
        if (alternateNames != null) {
            for (String candidate : alternateNames.split(",")) {
                String name = blankToNull(candidate);
                if (name != null && names.add(name) && names.size() >= MAX_ALTERNATE_NAMES) {
                    break;
                }
            }
        }
        if (names.isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO geographic_names (entity_type, entity_id, name) VALUES ('city', ?, ?)")) {
            for (String name : names) {
                insert.setInt(1, cityId);
                insert.setString(2, name);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static Integer countryId(Connection c, String iso) throws SQLException {
        if (iso == null || !iso.matches("[A-Z]{2}")) return null;
        try (PreparedStatement q = c.prepareStatement("SELECT id FROM countries WHERE iso_code=?")) {
            q.setString(1, iso); try (ResultSet r = q.executeQuery()) { return r.next() ? r.getInt(1) : null; }
        }
    }
    private static String required(String value, String field, int line) throws IOException { String result = blankToNull(value); if (result == null) throw new IOException("GeoNames line " + line + " has no " + field); return result; }
    private static String blankToNull(String value) { String result = value == null ? "" : value.trim(); return result.isEmpty() ? null : result; }
    private static double parseCoordinate(String value, double min, double max, int line) throws IOException { try { double n = Double.parseDouble(value); if (!Double.isFinite(n) || n < min || n > max) throw new NumberFormatException(); return n; } catch (NumberFormatException e) { throw new IOException("GeoNames line " + line + " has invalid coordinate", e); } }
    private static long parsePopulation(String value, int line) throws IOException { try { long n = Long.parseLong(value); if (n < 0) throw new NumberFormatException(); return n; } catch (NumberFormatException e) { throw new IOException("GeoNames line " + line + " has invalid population", e); } }
}
