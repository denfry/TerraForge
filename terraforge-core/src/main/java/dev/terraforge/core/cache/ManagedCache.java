package dev.terraforge.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.function.Function;

/**
 * A named cache owned by {@link CacheManager}.
 *
 * <p>Thin wrapper over Caffeine rather than a cache of our own: eviction, weighing and statistics
 * are solved problems, and the wrapper exists so every cache in TerraForge is registered, reportable
 * and invalidated together on reload.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class ManagedCache<K, V> {

    private final String name;
    private final Cache<K, V> delegate;
    private final boolean weighed;

    ManagedCache(String name, Cache<K, V> delegate, boolean weighed) {
        this.name = name;
        this.delegate = delegate;
        this.weighed = weighed;
    }

    public String name() {
        return name;
    }

    /** Cached value, computing it with {@code loader} on a miss. */
    public V get(K key, Function<? super K, ? extends V> loader) {
        return delegate.get(key, loader);
    }

    /** Cached value, or null when absent -- no loading. */
    public V getIfPresent(K key) {
        return delegate.getIfPresent(key);
    }

    public void put(K key, V value) {
        delegate.put(key, value);
    }

    public void invalidate(K key) {
        delegate.invalidate(key);
    }

    public void invalidateAll() {
        delegate.invalidateAll();
    }

    public long size() {
        return delegate.estimatedSize();
    }

    public CacheStatistics statistics() {
        var stats = delegate.stats();
        long sizeBytes = -1;
        if (weighed) {
            var eviction = delegate.policy().eviction();
            if (eviction.isPresent()) {
                sizeBytes = eviction.get().weightedSize().orElse(-1);
            }
        }
        return new CacheStatistics(
                name, delegate.estimatedSize(), stats.hitCount(), stats.missCount(),
                stats.evictionCount(), sizeBytes);
    }
}
