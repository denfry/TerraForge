package dev.terraforge.core.cache;

import java.util.Locale;

/**
 * Snapshot of one cache's behaviour, taken for logging and the {@code /earth cache} command.
 *
 * @param name        cache name, as registered with {@link CacheManager}
 * @param entries     entries currently resident
 * @param hits        cumulative hits
 * @param misses      cumulative misses
 * @param evictions   cumulative evictions
 * @param sizeBytes   estimated retained size, or -1 when the cache does not weigh its entries
 */
public record CacheStatistics(
        String name,
        long entries,
        long hits,
        long misses,
        long evictions,
        long sizeBytes) {

    public long requests() {
        return hits + misses;
    }

    /** Hit rate in [0,1]; 1.0 for a cache that has never been queried. */
    public double hitRate() {
        long requests = requests();
        return requests == 0 ? 1.0 : (double) hits / requests;
    }

    public String format() {
        return String.format(Locale.ROOT, "%s: %d entries, %.1f%% hit rate (%d/%d), %d evictions%s",
                name, entries, hitRate() * 100.0, hits, requests(), evictions,
                sizeBytes < 0 ? "" : String.format(Locale.ROOT, ", ~%.1f MB", sizeBytes / 1_048_576.0));
    }
}
