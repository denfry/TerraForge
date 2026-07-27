package dev.terraforge.geo.water;

import dev.terraforge.core.data.WaterProvider.WaterType;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;

/** Loads vetted water geometries from the prepared, read-only TerraForge SQLite database. */
public final class SqliteWaterProvider {

    private SqliteWaterProvider() {
    }

    /**
     * Loads all natural water bodies once. An empty table returns {@code null}, allowing callers to
     * retain the explicit elevation-derived fallback rather than treating absent vector data as land.
     */
    public static IndexedWaterProvider load(Path database) throws IOException {
        List<IndexedWaterProvider.WaterFeature> features = new ArrayList<>();
        WKBReader reader = new WKBReader();
        String url = "jdbc:sqlite:file:" + database.toAbsolutePath().normalize().toUri().getRawPath()
                + "?mode=ro";
        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT water_type, geometry FROM water_bodies");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                WaterType type = WaterType.valueOf(rows.getString("water_type"));
                Geometry geometry = reader.read(rows.getBytes("geometry"));
                if (type != WaterType.NONE && !geometry.isEmpty()) {
                    features.add(new IndexedWaterProvider.WaterFeature(type, geometry));
                }
            }
        } catch (SQLException | IllegalArgumentException | ParseException exception) {
            throw new IOException("Cannot load natural water data from " + database, exception);
        }
        return features.isEmpty() ? null : new IndexedWaterProvider(features);
    }
}
