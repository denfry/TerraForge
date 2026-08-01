package dev.terraforge.cli.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;

/** Imports WOKAM GeoJSON polygons and OSM {@code natural=cave_entrance} GeoJSON points. */
public final class KarstGeoJsonImporter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private KarstGeoJsonImporter() { }
    public static int importKarst(Path source, Connection connection, Envelope clip) throws IOException, java.sql.SQLException {
        int imported = 0; Clip selected = Clip.of(clip);
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO karst_areas (min_lat,min_lon,max_lat,max_lon,geometry) VALUES (?,?,?,?,?)")) {
            for (JsonNode feature : features(source)) {
                Geometry geometry = WaterGeoJsonImporter.readPolygonGeometry(feature.path("geometry"));
                if (!Clip.keeps(selected, geometry)) continue;
                if (clip != null && !clip.covers(geometry.getEnvelopeInternal())) geometry = geometry.intersection(GEOMETRY.toGeometry(clip));
                if (geometry.isEmpty() || !geometry.isValid() || geometry.getDimension() != 2) continue;
                Envelope b = geometry.getEnvelopeInternal();
                insert.setDouble(1,b.getMinY()); insert.setDouble(2,b.getMinX()); insert.setDouble(3,b.getMaxY()); insert.setDouble(4,b.getMaxX()); insert.setBytes(5,new WKBWriter().write(geometry)); insert.executeUpdate(); imported++;
            }
        } return imported;
    }
    public static int importEntrances(Path source, Connection connection, Envelope clip) throws IOException, java.sql.SQLException {
        int imported = 0;
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO cave_entrances (latitude,longitude,name) VALUES (?,?,?)")) {
            for (JsonNode feature : features(source)) {
                if (!"cave_entrance".equals(feature.path("properties").path("natural").asText())) continue;
                JsonNode c = feature.path("geometry").path("coordinates");
                if (!"Point".equals(feature.path("geometry").path("type").asText()) || !c.isArray() || c.size()<2) continue;
                double lon=c.get(0).asDouble(Double.NaN), lat=c.get(1).asDouble(Double.NaN);
                if (!Double.isFinite(lat)||!Double.isFinite(lon)||lat < -90||lat >90||lon < -180||lon >180 || (clip != null && !clip.contains(new Coordinate(lon,lat)))) continue;
                insert.setDouble(1,lat); insert.setDouble(2,lon); insert.setString(3,feature.path("properties").path("name").asText(null)); insert.executeUpdate(); imported++;
            }
        } return imported;
    }
    private static JsonNode features(Path source) throws IOException { JsonNode root=JSON.readTree(source.toFile()); if (!"FeatureCollection".equals(root.path("type").asText()) || !root.path("features").isArray()) throw new IOException(source+" must be a GeoJSON FeatureCollection"); return root.path("features"); }
}
