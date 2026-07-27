package dev.terraforge.cli.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Imports only explicitly labelled country and first-level-region polygon boundaries. */
public final class BoundaryGeoJsonImporter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private BoundaryGeoJsonImporter() { }

    public static int importFile(Path source, Connection connection) throws IOException, SQLException {
        return importFile(source, connection, null);
    }

    /**
     * @param clip optional lon/lat envelope; features whose bounding box misses it are skipped.
     *             A region always sits inside its country, so a region that survives the clip
     *             always finds its country in the same pass.
     */
    public static int importFile(Path source, Connection connection, Envelope clip)
            throws IOException, SQLException {
        JsonNode root = JSON.readTree(source.toFile());
        if (!"FeatureCollection".equals(root.path("type").asText()) || !root.path("features").isArray()) {
            throw new IOException(source + " must be a GeoJSON FeatureCollection");
        }
        List<Feature> countries = new ArrayList<>();
        List<Feature> regions = new ArrayList<>();
        for (JsonNode node : root.path("features")) {
            JsonNode properties = node.path("properties");
            String type = properties.path("boundary_type").asText("").trim().toUpperCase(Locale.ROOT);
            Geometry geometry = WaterGeoJsonImporter.readPolygonGeometry(node.path("geometry"));
            if (!geometry.isValid() || geometry.isEmpty() || geometry.getArea() == 0.0) {
                throw new IOException(source + " contains an invalid administrative boundary geometry");
            }
            Feature feature = new Feature(text(properties.path("name")), text(properties.path("iso_code")),
                    text(properties.path("country_iso_code")), properties.path("admin_level").asInt(1), geometry);
            if (!"COUNTRY".equals(type) && !"REGION".equals(type)) {
                throw new IOException("boundary_type must be COUNTRY or REGION");
            }
            // Clipped last, so a malformed feature is still rejected even when it lies outside the box.
            if (clip != null && !clip.intersects(geometry.getEnvelopeInternal())) continue;
            if ("COUNTRY".equals(type)) countries.add(feature);
            else regions.add(feature);
        }
        for (Feature country : countries) insertCountry(connection, country);
        for (Feature region : regions) insertRegion(connection, region);
        return countries.size() + regions.size();
    }

    private static void insertCountry(Connection connection, Feature feature) throws SQLException, IOException {
        if (feature.name == null || feature.isoCode == null || !feature.isoCode.matches("[A-Z]{2}")) {
            throw new IOException("COUNTRY requires name and uppercase two-letter iso_code");
        }
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

    private static void insertRegion(Connection connection, Feature feature) throws SQLException, IOException {
        if (feature.name == null || feature.countryIsoCode == null || !feature.countryIsoCode.matches("[A-Z]{2}")
                || feature.adminLevel < 1 || feature.adminLevel > 10) {
            throw new IOException("REGION requires name, country_iso_code and admin_level from 1 to 10");
        }
        int countryId;
        try (PreparedStatement country = connection.prepareStatement("SELECT id FROM countries WHERE iso_code = ?")) {
            country.setString(1, feature.countryIsoCode);
            try (ResultSet rows = country.executeQuery()) {
                if (!rows.next()) throw new IOException("REGION refers to an unknown country_iso_code " + feature.countryIsoCode);
                countryId = rows.getInt(1);
            }
        }
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
    private record Feature(String name, String isoCode, String countryIsoCode, int adminLevel, Geometry geometry) { }
}
