package dev.terraforge.geo.water;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.cache.ManagedCache;
import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.data.WaterProvider;
import dev.terraforge.core.data.WaterProvider.WaterType;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.prep.PreparedGeometry;

/** Opens vetted natural water geometries from the prepared, read-only TerraForge SQLite database. */
public final class SqliteWaterProvider {

    private SqliteWaterProvider() {
    }

    /** Convenience for offline tooling and tests: an empty table still returns {@code null}. */
    public static WaterProvider load(Path database) throws IOException {
        return load(database, new CacheManager(64), 4096);
    }

    /**
     * Catalogues every feature's bounding box -- cheap, indexed columns -- then returns a provider
     * that decodes and caches actual geometry lazily, on first lookup. An empty table returns
     * {@code null}, allowing callers to retain the explicit elevation-derived fallback rather than
     * treating absent vector data as land.
     *
     * @param maxResidentFeatures {@code cache.water-feature-cache-entries}
     */
    public static WaterProvider load(Path database, CacheManager cacheManager, int maxResidentFeatures)
            throws IOException {
        return load(database, cacheManager, maxResidentFeatures, 0.0);
    }

    /**
     * @param maxResidentFeatures         {@code cache.water-feature-cache-entries}
     * @param minVisibleRiverDischargeCms {@code water.min-visible-river-discharge-cms}: a prepared
     *                                    river below this HydroRIVERS discharge is catalogued out --
     *                                    not rendered by this world -- but the row is never touched,
     *                                    so raising the threshold later needs no re-preparation
     */
    public static WaterProvider load(Path database, CacheManager cacheManager, int maxResidentFeatures,
                                      double minVisibleRiverDischargeCms) throws IOException {
        if (maxResidentFeatures <= 0) {
            throw new IllegalArgumentException(
                    "cache.water-feature-cache-entries must be positive: " + maxResidentFeatures);
        }
        String url = "jdbc:sqlite:file:" + database.toAbsolutePath().normalize().toUri().getRawPath()
                + "?mode=ro";
        Connection connection = null;
        try {
            connection = DriverManager.getConnection(url);
            List<LazySqliteWaterProvider.CatalogEntry> entries =
                    catalogue(connection, minVisibleRiverDischargeCms);
            if (entries.isEmpty()) {
                connection.close();
                return null;
            }
            PreparedStatement geometryById = connection.prepareStatement(
                    "SELECT geometry FROM water_bodies WHERE id = ?");
            ManagedCache<Long, Optional<PreparedGeometry>> geometries =
                    cacheManager.newCache("water-features", maxResidentFeatures);
            return new LazySqliteWaterProvider(connection, geometryById, entries, geometries);
        } catch (IOException exception) {
            closeQuietly(connection);
            throw new IOException("Cannot load natural water data from " + database + ": "
                    + exception.getMessage(), exception);
        } catch (SQLException exception) {
            closeQuietly(connection);
            throw new IOException("Cannot load natural water data from " + database, exception);
        } catch (RuntimeException exception) {
            closeQuietly(connection);
            throw exception;
        }
    }

    /** Reads bounding boxes only -- never the geometry column -- so cataloguing costs milliseconds. */
    private static List<LazySqliteWaterProvider.CatalogEntry> catalogue(Connection connection,
            double minVisibleRiverDischargeCms) throws SQLException, IOException {
        requirePreparedColumns(connection);
        List<LazySqliteWaterProvider.CatalogEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, water_type, min_lat, min_lon, max_lat, max_lon, bed_depth_m, "
                        + "surface_elevation_m, discharge_cms FROM water_bodies");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                WaterType type = WaterType.valueOf(rows.getString("water_type"));
                if (type == WaterType.NONE) {
                    continue;
                }
                double discharge = rows.getDouble("discharge_cms");
                if (type == WaterType.RIVER && discharge < minVisibleRiverDischargeCms) {
                    continue;
                }
                Envelope envelope = new Envelope(
                        rows.getDouble("min_lon"), rows.getDouble("max_lon"),
                        rows.getDouble("min_lat"), rows.getDouble("max_lat"));
                // SQL NULL reads as 0.0 through getDouble, which for a water surface is sea level --
                // the exact wrong answer. wasNull() is the only way to tell "at sea level" from
                // "unknown", and the runtime treats the two very differently.
                double surface = rows.getDouble("surface_elevation_m");
                if (rows.wasNull()) {
                    surface = ElevationProvider.NO_DATA;
                }
                entries.add(new LazySqliteWaterProvider.CatalogEntry(
                        rows.getLong("id"), type, envelope, surface,
                        rows.getDouble("bed_depth_m"), discharge));
            }
        }
        return entries;
    }

    /**
     * Fails closed on a database prepared by an older TerraForge.
     *
     * <p>Without {@code surface_elevation_m} every lake's altitude is unknown, and a world generated
     * from such a database would silently drop every lake. Saying so is better than generating it.
     */
    private static void requirePreparedColumns(Connection connection) throws SQLException, IOException {
        Set<String> columns = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(water_bodies)");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                columns.add(rows.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        if (!columns.containsAll(List.of("surface_elevation_m", "bed_depth_m"))) {
            throw new IOException("prepared water data predates lake surface elevations "
                    + "(water_bodies has no surface_elevation_m/bed_depth_m); re-run "
                    + "`terraforge prepare-geo` against this database");
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Best-effort cleanup of a connection we are already abandoning.
        }
    }
}
