package dev.terraforge.cli.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.terraforge.cli.geo.BoundaryAttributes.Identity;
import dev.terraforge.cli.geo.BoundaryAttributes.Kind;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKBWriter;

/**
 * Imports country and first-level-region polygon boundaries.
 *
 * <p>Two schemas are accepted: TerraForge's own explicitly typed one, and Natural Earth's, which is
 * what the documentation recommends and what {@code fetch} downloads. {@link BoundaryAttributes}
 * decides which is in front of it and how strictly it is read -- a hand-authored file must be
 * correct, a published one only has to be usable.
 */
public final class BoundaryGeoJsonImporter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private BoundaryGeoJsonImporter() { }

    /**
     * @param imported features written to the database
     * @param skipped  features a published dataset carries that TerraForge cannot place: disputed
     *                 territories with no ISO code, deeper administrative levels, duplicates
     */
    public record Result(int imported, int skipped) { }

    public static Result importFile(Path source, Connection connection) throws IOException, SQLException {
        return importFile(source, connection, null);
    }

    /**
     * @param clipEnvelope optional lon/lat envelope; features that do not reach into it are skipped.
     *                     A region always sits inside its country, so a region that survives the
     *                     clip always finds its country in the same pass.
     */
    public static Result importFile(Path source, Connection connection, Envelope clipEnvelope)
            throws IOException, SQLException {
        Clip clip = Clip.of(clipEnvelope);
        JsonNode root = JSON.readTree(source.toFile());
        if (!"FeatureCollection".equals(root.path("type").asText()) || !root.path("features").isArray()) {
            throw new IOException(source + " must be a GeoJSON FeatureCollection");
        }
        List<Feature> countries = new ArrayList<>();
        List<Feature> regions = new ArrayList<>();
        int skipped = 0;
        int features = root.path("features").size();
        for (JsonNode node : root.path("features")) {
            JsonNode properties = node.path("properties");
            boolean strict = BoundaryAttributes.isNative(properties);

            Geometry geometry;
            try {
                geometry = WaterGeoJsonImporter.readPolygonGeometry(node.path("geometry"));
            } catch (IOException exception) {
                if (strict) {
                    throw exception;
                }
                skipped++;
                continue;
            }
            if (!geometry.isValid() || geometry.isEmpty() || geometry.getArea() == 0.0) {
                if (strict) {
                    throw new IOException(source + " contains an invalid administrative boundary geometry");
                }
                skipped++;
                continue;
            }

            Identity identity = strict ? nativeIdentity(properties) : BoundaryAttributes.naturalEarth(properties);
            if (identity.kind() == Kind.UNIDENTIFIED) {
                skipped++;
                continue;
            }
            // Clipped last, so a malformed feature is still rejected even when it lies outside the box.
            if (!Clip.keeps(clip, geometry)) continue;

            Feature feature = new Feature(identity.name(), identity.isoCode(), identity.countryIsoCode(),
                    identity.adminLevel(), geometry, strict);
            if (identity.kind() == Kind.COUNTRY) countries.add(feature);
            else regions.add(feature);
        }

        int imported = 0;
        for (Feature country : countries) {
            // The schema makes iso_code unique, and a published dataset can list a territory twice.
            // Failing would abort the whole transaction over a feature nothing needs.
            if (countryId(connection, country.isoCode) != null) {
                skipped++;
                continue;
            }
            insertCountry(connection, country);
            imported++;
        }
        for (Feature region : regions) {
            Integer countryId = countryId(connection, region.countryIsoCode);
            if (countryId == null) {
                if (region.strict) {
                    throw new IOException("REGION refers to an unknown country_iso_code " + region.countryIsoCode);
                }
                // Its country was skipped or lies outside the box; a region with no country cannot
                // be looked up anyway.
                skipped++;
                continue;
            }
            insertRegion(connection, region, countryId);
            imported++;
        }
        // Nothing usable at all means the file is not a boundary dataset TerraForge understands --
        // a loud failure, unlike the ordinary case of a few unplaceable territories.
        if (imported == 0 && skipped == features && skipped > 0) {
            throw new IOException(source + " declares neither boundary_type nor Natural Earth "
                    + "attributes on any feature; TerraForge cannot tell what these polygons are");
        }
        return new Result(imported, skipped);
    }

    /** TerraForge's own schema, read exactly as strictly as before. */
    private static Identity nativeIdentity(JsonNode properties) throws IOException {
        String type = properties.path("boundary_type").asText("").trim().toUpperCase(Locale.ROOT);
        if (!"COUNTRY".equals(type) && !"REGION".equals(type)) {
            throw new IOException("boundary_type must be COUNTRY or REGION");
        }
        Identity identity = new Identity("COUNTRY".equals(type) ? Kind.COUNTRY : Kind.REGION,
                text(properties.path("name")), text(properties.path("iso_code")),
                text(properties.path("country_iso_code")), properties.path("admin_level").asInt(1));
        if (identity.kind() == Kind.COUNTRY
                && (identity.name() == null || identity.isoCode() == null
                    || !identity.isoCode().matches("[A-Z]{2}"))) {
            throw new IOException("COUNTRY requires name and uppercase two-letter iso_code");
        }
        if (identity.kind() == Kind.REGION
                && (identity.name() == null || identity.countryIsoCode() == null
                    || !identity.countryIsoCode().matches("[A-Z]{2}")
                    || identity.adminLevel() < 1 || identity.adminLevel() > 10)) {
            throw new IOException("REGION requires name, country_iso_code and admin_level from 1 to 10");
        }
        return identity;
    }

    private static Integer countryId(Connection connection, String isoCode) throws SQLException {
        try (PreparedStatement country = connection.prepareStatement("SELECT id FROM countries WHERE iso_code = ?")) {
            country.setString(1, isoCode);
            try (ResultSet rows = country.executeQuery()) {
                return rows.next() ? rows.getInt(1) : null;
            }
        }
    }

    private static void insertCountry(Connection connection, Feature feature) throws SQLException, IOException {
        Envelope b = feature.geometry.getEnvelopeInternal();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO countries (iso_code, name, min_lat, min_lon, max_lat, max_lon, geometry)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, feature.isoCode); statement.setString(2, feature.name);
            statement.setDouble(3, b.getMinY()); statement.setDouble(4, b.getMinX());
            statement.setDouble(5, b.getMaxY()); statement.setDouble(6, b.getMaxX());
            statement.setBytes(7, new WKBWriter().write(feature.geometry)); statement.executeUpdate();
        }
    }

    private static void insertRegion(Connection connection, Feature feature, int countryId)
            throws SQLException, IOException {
        Envelope b = feature.geometry.getEnvelopeInternal();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO regions (country_id, name, admin_level, min_lat, min_lon, max_lat, max_lon, geometry)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setInt(1, countryId); statement.setString(2, feature.name); statement.setInt(3, feature.adminLevel);
            statement.setDouble(4, b.getMinY()); statement.setDouble(5, b.getMinX());
            statement.setDouble(6, b.getMaxY()); statement.setDouble(7, b.getMaxX());
            statement.setBytes(8, new WKBWriter().write(feature.geometry)); statement.executeUpdate();
        }
    }

    private static String text(JsonNode node) { String value = node.asText("").trim(); return value.isEmpty() ? null : value; }

    private record Feature(String name, String isoCode, String countryIsoCode, int adminLevel,
                           Geometry geometry, boolean strict) { }
}
