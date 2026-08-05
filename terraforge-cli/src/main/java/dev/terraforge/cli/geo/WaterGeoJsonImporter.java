package dev.terraforge.cli.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.terraforge.core.data.WaterProvider.WaterType;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKBWriter;

/**
 * Imports natural polygonal water features from GeoJSON into SQLite.
 *
 * <p>Two schemas are accepted, exactly as for administrative boundaries: TerraForge's own explicit
 * {@code water_type}, read strictly, and Natural Earth's {@code featurecla}, read leniently. A file
 * that declares neither is rejected -- being handed an unrecognised dataset should fail loudly, not
 * quietly produce a world with no lakes.
 */
public final class WaterGeoJsonImporter {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final ObjectMapper JSON = new ObjectMapper();

    private WaterGeoJsonImporter() {
    }

    /** @param skipped features the dataset carries that TerraForge does not place */
    public record Result(int imported, int skipped) { }

    public static Result importFile(Path source, Connection connection, Envelope clip)
            throws IOException, SQLException {
        return importFile(source, connection, clip, 1.0);
    }

    /**
     * @param blocksPerKm configured horizontal scale, used to preserve a one-block river minimum
     */
    public static Result importFile(Path source, Connection connection, Envelope clip, double blocksPerKm)
            throws IOException, SQLException {
        JsonNode root = JSON.readTree(source.toFile());
        if (!"FeatureCollection".equals(root.path("type").asText())) {
            throw new IOException(source + " must be a GeoJSON FeatureCollection");
        }
        JsonNode features = root.path("features");
        if (!features.isArray()) {
            throw new IOException(source + " has no features array");
        }
        int imported = 0;
        int skipped = 0;
        int recognised = 0;
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO water_bodies
                    (name, water_type, min_lat, min_lon, max_lat, max_lon, river_bed_depth_m, discharge_cms, geometry)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (JsonNode feature : features) {
                JsonNode properties = feature.path("properties");
                boolean strict = properties.hasNonNull("water_type");
                if (strict || properties.has("featurecla") || properties.has("DIS_AV_CMS")
                        || properties.has("dis_av_cms")) {
                    recognised++;
                }
                if (isManMadeWater(properties)) {
                    skipped++;
                    continue;
                }
                WaterType type = strict ? parseType(properties.path("water_type")) : publishedType(properties);
                if (type == null) {
                    skipped++;
                    continue;
                }
                Geometry geometry;
                try {
                    geometry = type == WaterType.RIVER
                            ? riverGeometry(feature.path("geometry"), properties, blocksPerKm)
                            : readPolygonGeometry(feature.path("geometry"));
                    validateAreaGeometry(geometry, source);
                } catch (IOException exception) {
                    if (strict) {
                        throw exception;
                    }
                    skipped++;
                    continue;
                }
                // Water is clipped rather than merely selected: half a lake at the edge of a region
                // is honest data. A feature the box already contains needs no overlay at all, which
                // is what a planet-wide box is -- every feature, and none of them cut.
                Clip selected = Clip.of(clip);
                if (!Clip.keeps(selected, geometry)) {
                    continue;
                }
                if (clip != null && !clip.covers(geometry.getEnvelopeInternal())) {
                    try {
                        geometry = geometry.intersection(GEOMETRY_FACTORY.toGeometry(clip));
                    } catch (RuntimeException exception) {
                        throw new IOException(source + " contains a water geometry that cannot be clipped", exception);
                    }
                }
                if (geometry.isEmpty() || geometry.getDimension() < 2 || geometry.getArea() == 0.0) {
                    continue;
                }
                try {
                    // Clipping can turn a barely-valid source polygon into an invalid one.
                    validateAreaGeometry(geometry, source);
                } catch (IOException exception) {
                    if (strict) {
                        throw exception;
                    }
                    skipped++;
                    continue;
                }
                Envelope bounds = geometry.getEnvelopeInternal();
                insert.setString(1, textOrNull(feature.path("properties").path("name")));
                insert.setString(2, type.name());
                insert.setDouble(3, bounds.getMinY());
                insert.setDouble(4, bounds.getMinX());
                insert.setDouble(5, bounds.getMaxY());
                insert.setDouble(6, bounds.getMaxX());
                double depth = type == WaterType.RIVER ? RiverWidth.bedDepthMetres(riverWidth(properties, blocksPerKm)) : 0.0;
                insert.setDouble(7, depth);
                insert.setDouble(8, type == WaterType.RIVER ? dischargeCubicMetresPerSecond(properties) : 0.0);
                insert.setBytes(9, new WKBWriter().write(geometry));
                insert.executeUpdate();
                imported++;
            }
        }
        if (imported == 0 && skipped == features.size() && skipped > 0 && recognised == 0) {
            throw new IOException(source + " declares neither water_type nor a Natural Earth "
                    + "featurecla or HydroRIVERS discharge on any feature; TerraForge cannot tell what these polygons are");
        }
        return new Result(imported, skipped);
    }

    /**
     * Natural Earth's own classification.
     *
     * <p>Reservoirs are deliberately not imported. They are man-made water, and TerraForge generates
     * the planet as it would be without people -- the same rule that keeps dams, roads and canals out
     * of the generator. The valley is generated; whoever wants the reservoir builds it.
     *
     * @return {@code null} when the feature is not water TerraForge places
     */
    private static WaterType publishedType(JsonNode properties) {
        if (isManMadeWater(properties)) {
            return null;
        }
        if (properties.has("DIS_AV_CMS") || properties.has("dis_av_cms")) {
            return WaterType.RIVER;
        }
        return switch (properties.path("featurecla").asText("").trim().toLowerCase(Locale.ROOT)) {
            case "lake", "alkaline lake", "playa" -> WaterType.LAKE;
            case "ocean" -> WaterType.OCEAN;
            default -> null;
        };
    }

    /** Published sources are leniently recognised, but never allowed to smuggle in human works. */
    private static boolean isManMadeWater(JsonNode properties) {
        return List.of("featurecla", "fclass", "FCLASS", "waterway", "type")
                .stream().map(properties::path).map(node -> node.asText("").trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("canal") || value.contains("reservoir"));
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

    private static Geometry riverGeometry(JsonNode node, JsonNode properties, double blocksPerKm) throws IOException {
        Geometry line = readLineGeometry(node);
        return riverPolygon(line, riverWidth(properties, blocksPerKm));
    }

    static Geometry riverPolygon(Geometry line, double widthMetres) throws IOException {
        // GeoJSON is WGS84.  A longitude degree is shortest at the poles, so use the line's
        // centroid to make the circular JTS buffer large enough in both axes.
        double cosine = Math.max(0.01, Math.abs(Math.cos(Math.toRadians(line.getCentroid().getY()))));
        double radiusDegrees = widthMetres / (2.0 * 111_320.0 * cosine);
        try {
            return line.buffer(radiusDegrees);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid HydroRIVERS line geometry", exception);
        }
    }

    private static double riverWidth(JsonNode properties, double blocksPerKm) {
        return RiverWidth.metres(dischargeCubicMetresPerSecond(properties), blocksPerKm);
    }

    private static double dischargeCubicMetresPerSecond(JsonNode properties) {
        return properties.path("DIS_AV_CMS").asDouble(properties.path("dis_av_cms").asDouble(0.0));
    }

    private static Geometry readLineGeometry(JsonNode node) throws IOException {
        return switch (node.path("type").asText()) {
            case "LineString" -> splitAntimeridian(line(node.path("coordinates")));
            case "MultiLineString" -> {
                JsonNode lines = node.path("coordinates");
                if (!lines.isArray() || lines.isEmpty()) throw new IOException("MultiLineString must have lines");
                LineString[] result = new LineString[lines.size()];
                for (int i = 0; i < lines.size(); i++) result[i] = line(lines.get(i));
                yield splitAntimeridian(GEOMETRY_FACTORY.createMultiLineString(result));
            }
            default -> throw new IOException("Rivers must be LineString or MultiLineString GeoJSON");
        };
    }

    private static LineString line(JsonNode positions) throws IOException {
        if (!positions.isArray() || positions.size() < 2) throw new IOException("LineString needs two positions");
        Coordinate[] coordinates = new Coordinate[positions.size()];
        for (int i = 0; i < positions.size(); i++) coordinates[i] = coordinate(positions.get(i));
        return GEOMETRY_FACTORY.createLineString(coordinates);
    }

    /** Splits rather than drawing a 358-degree chord when a river crosses +/-180. */
    static Geometry splitAntimeridian(Geometry geometry) {
        List<LineString> result = new java.util.ArrayList<>();
        for (int part = 0; part < geometry.getNumGeometries(); part++) {
            Coordinate[] input = geometry.getGeometryN(part).getCoordinates();
            List<Coordinate> current = new java.util.ArrayList<>();
            current.add(input[0]);
            for (int i = 1; i < input.length; i++) {
                Coordinate previous = input[i - 1]; Coordinate next = input[i];
                double delta = next.x - previous.x;
                if (Math.abs(delta) > 180.0) {
                    double edge = delta < 0.0 ? 180.0 : -180.0;
                    double adjusted = next.x + (delta < 0.0 ? 360.0 : -360.0);
                    double fraction = (edge - previous.x) / (adjusted - previous.x);
                    double latitude = previous.y + fraction * (next.y - previous.y);
                    current.add(new Coordinate(edge, latitude));
                    result.add(GEOMETRY_FACTORY.createLineString(current.toArray(Coordinate[]::new)));
                    current = new java.util.ArrayList<>();
                    current.add(new Coordinate(-edge, latitude));
                }
                current.add(next);
            }
            result.add(GEOMETRY_FACTORY.createLineString(current.toArray(Coordinate[]::new)));
        }
        return result.size() == 1 ? result.getFirst() : GEOMETRY_FACTORY.createMultiLineString(result.toArray(LineString[]::new));
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
            coordinates[i] = coordinate(positions.get(i));
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

    private static Coordinate coordinate(JsonNode position) throws IOException {
        if (!position.isArray() || position.size() < 2 || !position.get(0).isNumber() || !position.get(1).isNumber()) {
            throw new IOException("GeoJSON position must contain numeric longitude and latitude");
        }
        double longitude = position.get(0).asDouble(); double latitude = position.get(1).asDouble();
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -90 || latitude > 90
                || longitude < -180 || longitude > 180) throw new IOException("GeoJSON coordinates must be valid WGS84 longitude/latitude values");
        return new Coordinate(longitude, latitude);
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
