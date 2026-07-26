package dev.terraforge.geo.dem;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import dev.terraforge.core.cache.CacheStatistics;
import dev.terraforge.core.cache.ManagedCache;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Bounded cache of loaded DEM tiles, sitting between the generator and {@link DemReader}.
 *
 * <p>Chunk generation walks a small neighbourhood of tiles over and over, so the hit rate in steady
 * state should exceed 95%; {@code /earth cache stats} exposes it. The bound is on entries rather
 * than bytes because a mapped tile costs address space, not heap, and entries are what an operator
 * can reason about.
 *
 * <p>A key that has no prepared file is cached as a negative result. Without that, every chunk in an
 * uncovered area would retry the load and log again.
 */
public final class DemCache implements ManagedCache {

    public static final String NAME = "dem";

    private final LoadingCache<DemTileKey, Optional<DemTile>> cache;
    private final AtomicLong residentBytes = new AtomicLong();
    private final int maxEntries;

    /**
     * @param reader     source of prepared tiles
     * @param maxEntries resident tile limit, from {@code cache.dem-tile-cache-entries}
     * @param onMissing  called once per key that has no prepared file, for the "Missing DEM tile"
     *                   warning; the negative cache entry guarantees "once"
     */
    public DemCache(DemReader reader, int maxEntries, Consumer<DemTileKey> onMissing) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("dem-tile-cache-entries must be greater than 0");
        }
        this.maxEntries = maxEntries;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .recordStats()
                .<DemTileKey, Optional<DemTile>>removalListener((key, tile, cause) -> {
                    if (tile != null) {
                        tile.ifPresent(evicted -> residentBytes.addAndGet(-evicted.sizeBytes()));
                    }
                })
                .build(key -> {
                    Optional<DemTile> tile;
                    try {
                        tile = reader.read(key);
                    } catch (IOException e) {
                        // Loud, not silent: a corrupt tile must not masquerade as missing data.
                        throw new UncheckedIOException("failed to read DEM tile " + key, e);
                    }
                    if (tile.isEmpty()) {
                        onMissing.accept(key);
                    } else {
                        residentBytes.addAndGet(tile.get().sizeBytes());
                    }
                    return tile;
                });
    }

    /** Tile covering this point, or empty when no prepared tile exists. */
    public Optional<DemTile> tileAt(double latitude, double longitude) {
        return get(DemTileKey.of(latitude, longitude));
    }

    public Optional<DemTile> get(DemTileKey key) {
        Optional<DemTile> tile = cache.get(key);
        return tile == null ? Optional.empty() : tile;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CacheStatistics statistics() {
        CacheStats stats = cache.stats();
        return new CacheStatistics(
                NAME,
                cache.estimatedSize(),
                maxEntries,
                stats.hitCount(),
                stats.missCount(),
                stats.evictionCount(),
                stats.loadFailureCount(),
                estimatedSizeBytes());
    }

    /** Runs pending eviction work now. Caffeine is otherwise free to defer it. */
    public void cleanUp() {
        cache.cleanUp();
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
        cache.cleanUp();
        residentBytes.set(0);
    }

    @Override
    public long estimatedSizeBytes() {
        return Math.max(0, residentBytes.get());
    }
}
