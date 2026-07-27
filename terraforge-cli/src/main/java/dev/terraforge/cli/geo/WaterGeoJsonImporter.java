package dev.terraforge.cli.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.terraforge.core.data.WaterProvider.WaterType;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKBWriter;

/** Imports explicitly labelled, natural polygonal water features from GeoJSON into SQLite. */
public final class WaterGeoJsonImporter {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final ObjectMapper JSON = new ObjectMapper();

    private WaterGeoJsonImporter() {
    }

    public static int importFile(Path source, Connection connection, Envelope clip) throws IOException, SQLException {
        JsonNode root = JSON.readTree(source.toFile());
        if (!"FeatureCollection".equals(root.path("type").asText())) {
            throw new IOException(source + " must be a GeoJSON FeatureCollection");
        }
        JsonNode features = root.path("features");
        if (!features.isArray()) {
            throw new IOException(source + " has no features array");
        }
        int imported = 0;
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO water_bodies (name, water_type, min_lat, min_lon, max_lat, max_lon, geometry)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (JsonNode feature : features) {
                WaterType type = parseType(feature.path("properties").path("water_type"));
                Geometry geometry = readPolygonGeometry(feature.path("geometry"));
                validateAreaGeometry(geometry, source);
                if (clip != null) {
                    try {
                        geometry = geometry.intersection(GEOMETRY_FACTORY.toGeometry(clip));
                    } catch (RuntimeException exception) {
                        throw new IOException(source + " contains a water geometry that cannot be clipped", exception);
                    }
                }
                if (geometry.isEmpty() || geometry.getDimension() < 2 || geometry.getArea() == 0.0) {
                    continue;
                }
                validateAreaGeometry(geometry, source);
                Envelope bounds = geometry.getEnvelopeInternal();
                insert.setString(1, textOrNull(feature.path("properties").path("name")));
                insert.setString(2, type.name());
                insert.setDouble(3, bounds.getMinY());
                insert.setDouble(4, bounds.getMinX());
                insert.setDouble(5, bounds.getMaxY());
                insert.setDouble(6, bounds.getMaxX());
                insert.setBytes(7, new WKBWriter().write(geometry));
                insert.executeUpdate();
                imported++;
            }
        }
        return imported;
    }

    private static WaterType parseType(JsonNode value) throws IOException {
        String type = value.asText("").trim().toUpperCase(Locale.ROOT);
        try {
            WaterType waterType = WaterType.valueOf(type);
            if (waterType == WaterType.NONE) {
                throw new IOException("water_type must be OCEAN, LAKE or RIVER");
            }
            return waterType;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Each water feature must declare water_type as OCEAN, LAKE or RIVER", exception);
        }
    }

    /** Shared restricted GeoJSON reader: only polygonal WGS84 features are ever admitted. */
    static Geometry readPolygonGeometry(JsonNode node) throws IOException {
        return switch (node.path("type").asText()) {
            case "Polygon" -> polygon(node.path("coordinates"));
            case "MultiPolygon" -> multiPolygon(node.path("coordinates"));
            default -> throw new IOException("Only Polygon and MultiPolygon water geometries are accepted");
        };
    }

    private static Polygon polygon(JsonNode rings) throws IOException {
        if (!rings.isArray() || rings.isEmpty()) {
            throw new IOException("Polygon must have at least one linear ring");
        }
        LinearRing shell = ring(rings.get(0));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = ring(rings.get(i));
        }
        return GEOMETRY_FACTORY.createPolygon(shell, holes);
    }

    private static MultiPolygon multiPolygon(JsonNode polygons) throws IOException {
        if (!polygons.isArray() || polygons.isEmpty()) {
            throw new IOException("MultiPolygon must have at least one polygon");
        }
        Polygon[] result = new Polygon[polygons.size()];
        for (int i = 0; i < polygons.size(); i++) {
            result[i] = polygon(polygons.get(i));
        }
        return GEOMETRY_FACTORY.createMultiPolygon(result);
    }

    private static LinearRing ring(JsonNode positions) throws IOException {
        if (!positions.isArray() || positions.size() < 4) {
            throw new IOException("Linear ring needs at least four positions");
        }
        Coordinate[] coordinates = new Coordinate[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            JsonNode position = positions.get(i);
            if (!position.isArray() || position.size() < 2 || !position.get(0).isNumber() || !position.get(1).isNumber()) {
                throw new IOException("GeoJSON position must contain numeric longitude and latitude");
            }
            double longitude = position.get(0).asDouble();
            double latitude = position.get(1).asDouble();
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                throw new IOException("GeoJSON coordinates must be valid WGS84 longitude/latitude values");
            }
            coordinates[i] = new Coordinate(longitude, latitude);
        }
        if (!coordinates[0].equals2D(coordinates[coordinates.length - 1])) {
            throw new IOException("Linear ring must end at its first position");
        }
        try {
            return GEOMETRY_FACTORY.createLinearRing(coordinates);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid linear ring", exception);
        }
    }

    private static void validateAreaGeometry(Geometry geometry, Path source) throws IOException {
        if (!geometry.isValid() || geometry.getDimension() < 2 || geometry.getArea() == 0.0) {
            throw new IOException(source + " contains an invalid or zero-area water geometry");
        }
    }

    private static String textOrNull(JsonNode node) {
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }
}
