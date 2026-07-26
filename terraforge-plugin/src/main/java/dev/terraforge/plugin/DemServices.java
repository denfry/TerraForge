package dev.terraforge.plugin;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.geodesy.Geodesy;
import dev.terraforge.geo.dem.DemCache;
import dev.terraforge.geo.dem.DemElevationProvider;
import dev.terraforge.geo.dem.DemTile;
import dev.terraforge.geo.dem.DemTileKey;
import dev.terraforge.geo.dem.FileDemReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Builds the elevation stack -- prepared tile directory, tile cache, elevation provider -- and keeps
 * the plugin entry point free of it.
 *
 * <p>Missing data is not a startup failure. A server with no prepared tiles enables normally and
 * generates flat terrain at {@code terrain.fallback-elevation}; the log says so plainly, because an
 * operator who has not run {@code prepare-dem} yet needs a hint, not a stack trace.
 */
public final class DemServices implements AutoCloseable {

    private static final String LOG_PREFIX = "[TerraForge-Geo] ";

    private final FileDemReader reader;
    private final DemCache cache;
    private final DemElevationProvider elevation;
    private final Path directory;

    private DemServices(FileDemReader reader, DemCache cache, DemElevationProvider elevation, Path directory) {
        this.reader = reader;
        this.cache = cache;
        this.elevation = elevation;
        this.directory = directory;
    }

    public static DemServices load(Path dataFolder, TerraForgeConfig config, CacheManager caches, Logger logger)
            throws IOException {
        Path directory = dataFolder.resolve(config.data().dataDirectory()).resolve("dem");
        FileDemReader reader = new FileDemReader(directory);
        DemCache cache = caches.register(new DemCache(
                reader,
                config.cache().demTileCacheEntries(),
                key -> logger.warning(LOG_PREFIX + "Missing DEM tile: " + key + " (no prepared file); "
                        + "falling back to terrain.fallback-elevation ("
                        + config.terrain().fallbackElevation() + " m)")));

        double resolution = estimateResolution(reader);
        DemElevationProvider elevation = DemElevationProvider.of(cache, reader, resolution, false);

        if (reader.tileCount() == 0) {
            logger.warning(LOG_PREFIX + "No .tfdem tiles in " + directory);
            logger.warning(LOG_PREFIX + "The world will be flat at terrain.fallback-elevation until you run "
                    + "'terraforge prepare-dem'. See docs/dem.md.");
        } else {
            logger.info(LOG_PREFIX + reader.tileCount() + " DEM tile(s) in " + directory
                    + ", coverage " + elevation.coverage()
                    + String.format(", ~%.0f m/sample", resolution));
        }
        return new DemServices(reader, cache, elevation, directory);
    }

    /**
     * Ground resolution taken from a tile that actually exists, rather than assumed. Reported in
     * the banner and used for pregeneration planning; a wrong guess here is only ever cosmetic.
     */
    private static double estimateResolution(FileDemReader reader) {
        Iterator<DemTileKey> keys = reader.availableTiles().iterator();
        if (!keys.hasNext()) {
            return Double.NaN;
        }
        DemTileKey key = keys.next();
        try {
            Optional<DemTile> tile = reader.read(key);
            if (tile.isEmpty()) {
                return Double.NaN;
            }
            double degreesPerSample = 1.0 / (tile.get().width() - 1);
            double latitude = key.latDegree() + 0.5;
            return degreesPerSample * Geodesy.MEAN_RADIUS * Math.PI / 180.0
                    * Math.cos(Math.toRadians(latitude));
        } catch (IOException e) {
            return Double.NaN;
        }
    }

    public DemElevationProvider elevation() {
        return elevation;
    }

    public DemCache cache() {
        return cache;
    }

    public Path directory() {
        return directory;
    }

    public int tileCount() {
        return reader.tileCount();
    }

    @Override
    public void close() throws IOException {
        cache.invalidateAll();
        reader.close();
    }
}
