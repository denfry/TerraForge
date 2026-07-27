package dev.terraforge.core.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Owns every cache in TerraForge.
 *
 * <p>Caches are created here rather than by their users so that all of them can be listed, reported
 * and dropped at once on {@code /earth reload}, and so the configured memory ceiling is enforced in
 * one place instead of being divided by guesswork across the code base.
 *
 * <p>The ceiling is a <em>soft</em> budget: it bounds the weighed caches (DEM tiles, chunk samples),
 * which are the ones that can grow to gigabytes. Small bookkeeping caches are entry-bounded.
 */
public final class CacheManager {

    private final long memoryBudgetBytes;
    private final Map<String, ManagedCache<?, ?>> caches = new ConcurrentHashMap<>();

    /** @param memoryLimitMb soft ceiling for all weighed caches together */
    public CacheManager(int memoryLimitMb) {
        if (memoryLimitMb <= 0) {
            throw new IllegalArgumentException("cache.memory-limit-mb must be positive: " + memoryLimitMb);
        }
        this.memoryBudgetBytes = memoryLimitMb * 1_048_576L;
    }

    public long memoryBudgetBytes() {
        return memoryBudgetBytes;
    }

    /**
     * Creates an entry-bounded cache.
     *
     * @param name       unique cache name, used in reports
     * @param maxEntries eviction bound
     */
    public <K, V> ManagedCache<K, V> newCache(String name, long maxEntries) {
        return register(name, new ManagedCache<>(name,
                Caffeine.newBuilder()
                        .maximumSize(maxEntries)
                        .recordStats()
                        .build(),
                false));
    }

    /**
     * Creates an entry-bounded cache whose entries also expire, for data that goes stale rather
     * than merely taking up room.
     */
    public <K, V> ManagedCache<K, V> newExpiringCache(String name, long maxEntries, long ttlSeconds) {
        return register(name, new ManagedCache<>(name,
                Caffeine.newBuilder()
                        .maximumSize(maxEntries)
                        .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                        .recordStats()
                        .build(),
                false));
    }

    /**
     * Creates a byte-bounded cache, for entries big enough that counting them says nothing useful
     * about memory (DEM tiles range from kilobytes to 50 MB).
     *
     * <p>Caffeine bounds by size <em>or</em> weight, not both, so a caller that also has an entry
     * limit converts it to bytes before calling -- see {@link #memoryBudgetBytes()}.
     *
     * @param maxWeightBytes retained-byte ceiling for this cache; clamped to the manager's budget
     * @param weigher        retained bytes per entry
     */
    public <K, V> ManagedCache<K, V> newWeighedCache(
            String name, long maxWeightBytes, Weigher<K, V> weigher) {
        if (maxWeightBytes <= 0) {
            throw new IllegalArgumentException("maxWeightBytes must be positive: " + maxWeightBytes);
        }
        long maxWeight = Math.min(maxWeightBytes, memoryBudgetBytes);
        return register(name, new ManagedCache<>(name,
                Caffeine.newBuilder()
                        .maximumWeight(maxWeight)
                        .weigher(weigher)
                        .recordStats()
                        .build(),
                true));
    }

    private <K, V> ManagedCache<K, V> register(String name, ManagedCache<K, V> cache) {
        if (caches.putIfAbsent(name, cache) != null) {
            throw new IllegalStateException("Cache already registered: " + name);
        }
        return cache;
    }

    /** Statistics for every registered cache, ordered by name. */
    public List<CacheStatistics> statistics() {
        return caches.values().stream()
                .map(ManagedCache::statistics)
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
    }

    /** Drops every cached entry; called on reload. Registrations survive. */
    public void invalidateAll() {
        caches.values().forEach(ManagedCache::invalidateAll);
    }
}
