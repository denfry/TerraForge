package dev.terraforge.geo.dem;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.cache.CacheStatistics;
import dev.terraforge.core.cache.ManagedCache;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.data.ElevationProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ElevationProvider} over prepared {@code .tfdem} tiles.
 *
 * <p>This is the only place that turns a geographic point into a DEM tile lookup. Tiles are cached
 * by {@link CacheManager} and weighed by their mapped size, so the resident set honours
 * {@code cache.memory-limit-mb} instead of growing with the number of tiles a world touches.
 *
 * <p>Missing data is reported as {@link ElevationProvider#NO_DATA}, never as a substituted value:
 * the terrain stage owns the fallback policy and needs to know whether a column was real. Each
 * missing tile is logged once -- a hole in the data would otherwise log per chunk, forever.
 */
public final class DemElevationProvider implements ElevationProvider {

    private static final System.Logger LOG = System.getLogger("TerraForge-DEM");

    /** Share of the cache budget the DEM tiles may occupy; the rest is chunk samples and geodata. */
    private static final double CACHE_BUDGET_FRACTION = 0.6;

    /**
     * Samples read per axis when averaging a block's footprint.
     *
     * <p>Sixteen is past the point of diminishing returns for anti-aliasing a kilometre-wide block
     * against arc-second data, and it bounds the work: without a cap, a coarse {@code blocks-per-km}
     * would make one column read a quarter of a tile.
     */
    private static final int MAX_FOOTPRINT_SAMPLES_PER_AXIS = 16;

    private final DemReader reader;
    private final ManagedCache<DemTileKey, Optional<DemTile>> tiles;
    private final Set<DemTileKey> reportedMissing = ConcurrentHashMap.newKeySet();
    private final GeoBounds coverage;

    /**
     * @param maxResidentTiles {@code cache.dem-tile-cache-entries}; converted to a byte ceiling
     *                         using the prepared tile size, then clamped to the cache budget, so
     *                         neither setting can be violated by the other
     */
    public DemElevationProvider(DemReader reader, CacheManager cacheManager, int maxResidentTiles) {
        if (maxResidentTiles <= 0) {
            throw new IllegalArgumentException(
                    "cache.dem-tile-cache-entries must be positive: " + maxResidentTiles);
        }
        this.reader = reader;
        long budgetBytes = (long) (cacheManager.memoryBudgetBytes() * CACHE_BUDGET_FRACTION);
        long tileBytes = reader.typicalTileBytes();
        long maxWeightBytes = tileBytes > 0
                ? Math.min(budgetBytes, maxResidentTiles * tileBytes)
                : budgetBytes;
        this.tiles = cacheManager.newWeighedCache("dem-tiles", Math.max(1L, maxWeightBytes),
                (key, tile) -> (int) Math.min(Integer.MAX_VALUE,
                        tile.map(DemTile::sizeBytes).orElse(1L)));
        this.coverage = computeCoverage(reader);
    }

    private static GeoBounds computeCoverage(DemReader reader) {
        int minLat = Integer.MAX_VALUE;
        int minLon = Integer.MAX_VALUE;
        int maxLat = Integer.MIN_VALUE;
        int maxLon = Integer.MIN_VALUE;
        for (DemTileKey key : reader.availableTiles()) {
            minLat = Math.min(minLat, key.latDegree());
            minLon = Math.min(minLon, key.lonDegree());
            maxLat = Math.max(maxLat, key.latDegree() + 1);
            maxLon = Math.max(maxLon, key.lonDegree() + 1);
        }
        if (minLat == Integer.MAX_VALUE) {
            // Nothing prepared: claim nothing rather than the world, so info reports the truth.
            return new GeoBounds(0.0, 0.0, 0.0, 0.0);
        }
        return new GeoBounds(minLat, minLon, maxLat, maxLon);
    }

    @Override
    public double elevationAt(double latitude, double longitude) {
        DemTileKey key = DemTileKey.of(latitude, longitude);
        Optional<DemTile> tile = tiles.get(key, this::load);
        return tile.map(t -> t.interpolate(latitude, longitude)).orElse(NO_DATA);
    }

    @Override
    public double averageElevationAt(double latitude, double longitude,
                                     double latitudeSpanDegrees, double longitudeSpanDegrees) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || !(latitudeSpanDegrees > 0.0) || !(longitudeSpanDegrees > 0.0)) {
            return elevationAt(latitude, longitude);
        }
        double minLatitude = GeoPoint.clampLatitude(latitude - latitudeSpanDegrees / 2.0);
        double maxLatitude = GeoPoint.clampLatitude(latitude + latitudeSpanDegrees / 2.0);
        double minLongitude = longitude - longitudeSpanDegrees / 2.0;
        double maxLongitude = longitude + longitudeSpanDegrees / 2.0;

        double sum = 0.0;
        int count = 0;
        // A block's footprint is a rounding error next to a one-degree tile, so this is one tile in
        // the overwhelming majority of columns and at most four at a tile corner. Walking the tiles
        // is also what finally makes the average correct across a tile border: a single tile clamps
        // its own edge and would otherwise average a duplicated line of samples.
        int lastLatDegree = (int) Math.floor(maxLatitude);
        int lastLonDegree = (int) Math.floor(maxLongitude);
        for (int latDegree = (int) Math.floor(minLatitude); latDegree <= lastLatDegree; latDegree++) {
            for (int lonDegree = (int) Math.floor(minLongitude); lonDegree <= lastLonDegree; lonDegree++) {
                Optional<DemTile> tile = tiles.get(new DemTileKey(latDegree, lonDegree), this::load);
                if (tile.isEmpty()) {
                    continue;
                }
                DemTile.SampleTotal total = tile.get().averageWithin(minLatitude, minLongitude,
                        maxLatitude, maxLongitude, MAX_FOOTPRINT_SAMPLES_PER_AXIS);
                sum += total.sum();
                count += total.count();
            }
        }
        // A footprint finer than the DEM grid covers no sample at all -- which is the normal case at
        // fine blocks-per-km settings, not an error. Interpolate the centre instead of reporting a
        // hole in prepared data.
        return count == 0 ? elevationAt(latitude, longitude) : sum / count;
    }

    private Optional<DemTile> load(DemTileKey key) {
        try {
            Optional<DemTile> tile = reader.read(key);
            if (tile.isEmpty() && reportedMissing.add(key)) {
                LOG.log(System.Logger.Level.INFO,
                        "Missing DEM tile: {0} (no prepared file) -- terrain there uses the fallback elevation",
                        key);
            }
            return tile;
        } catch (IOException e) {
            if (reportedMissing.add(key)) {
                LOG.log(System.Logger.Level.WARNING, "Failed to load DEM tile " + key + ": " + e.getMessage());
            }
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean hasCoverage(double latitude, double longitude) {
        return reader.exists(DemTileKey.of(latitude, longitude));
    }

    @Override
    public GeoBounds coverage() {
        return coverage;
    }

    @Override
    public boolean hasBathymetry() {
        return reader.hasBathymetry();
    }

    /** Tiles that were requested but are not prepared; the coverage gaps of the running world. */
    public Set<DemTileKey> missingTiles() {
        return Set.copyOf(reportedMissing);
    }

    public CacheStatistics cacheStatistics() {
        return tiles.statistics();
    }

    /** Drops cached tiles and the missing-tile report; called on reload. */
    public void invalidate() {
        tiles.invalidateAll();
        reportedMissing.clear();
    }
}
