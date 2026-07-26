package dev.terraforge.geo.dem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.cache.CacheStatistics;
import dev.terraforge.core.coord.GeoBounds;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The DEM cache is on the per-chunk path, so its bound, its hit accounting and its handling of
 * absent tiles all have to hold -- an unbounded or endlessly-retrying tile cache would be felt as
 * lag rather than as a failure.
 */
class DemCacheTest {

    /** A reader serving synthetic tiles for a fixed set of keys. */
    private static final class FakeReader implements DemReader {
        private final List<DemTileKey> keys;
        private final AtomicInteger reads = new AtomicInteger();

        FakeReader(DemTileKey... keys) {
            this.keys = List.of(keys);
        }

        @Override
        public Optional<DemTile> read(DemTileKey key) {
            if (!keys.contains(key)) {
                return Optional.empty();
            }
            reads.incrementAndGet();
            return Optional.of(new FlatTile(key, 100.0));
        }

        @Override
        public boolean exists(DemTileKey key) {
            return keys.contains(key);
        }

        @Override
        public Iterable<DemTileKey> availableTiles() {
            return keys;
        }

        @Override
        public void close() {
        }
    }

    /** A tile of constant elevation; enough to exercise the cache without touching the disk. */
    private record FlatTile(DemTileKey key, double elevation) implements DemTile {
        @Override
        public GeoBounds bounds() {
            return key.bounds();
        }

        @Override
        public int width() {
            return 2;
        }

        @Override
        public int height() {
            return 2;
        }

        @Override
        public double sample(int x, int y) {
            return elevation;
        }

        @Override
        public double interpolate(double latitude, double longitude) {
            return bounds().contains(latitude, longitude) ? elevation : Double.NaN;
        }

        @Override
        public long sizeBytes() {
            return 1024;
        }
    }

    @Test
    void aRepeatedLookupHitsTheCache() {
        FakeReader reader = new FakeReader(new DemTileKey(50, 8));
        DemCache cache = new DemCache(reader, 16, key -> { });

        for (int i = 0; i < 10; i++) {
            assertThat(cache.get(new DemTileKey(50, 8))).isPresent();
        }

        assertThat(reader.reads).hasValue(1);
        CacheStatistics stats = cache.statistics();
        assertThat(stats.hits()).isEqualTo(9);
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.hitRate()).isEqualTo(0.9);
    }

    @Test
    void aMissingTileIsReportedOnceAndThenRemembered() {
        List<DemTileKey> reported = new ArrayList<>();
        DemCache cache = new DemCache(new FakeReader(), 16, reported::add);

        for (int i = 0; i < 5; i++) {
            assertThat(cache.get(new DemTileKey(52, 13))).isEmpty();
        }

        assertThat(reported).containsExactly(new DemTileKey(52, 13));
    }

    @Test
    void theCacheStaysWithinItsEntryBound() {
        DemTileKey[] keys = new DemTileKey[32];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = new DemTileKey(50, i);
        }
        DemCache cache = new DemCache(new FakeReader(keys), 8, key -> { });

        for (DemTileKey key : keys) {
            cache.get(key);
        }
        cache.cleanUp();

        assertThat(cache.statistics().entries()).isLessThanOrEqualTo(8);
        assertThat(cache.statistics().maxEntries()).isEqualTo(8);
    }

    @Test
    void residentBytesFallBackToZeroAfterInvalidation() {
        DemCache cache = new DemCache(new FakeReader(new DemTileKey(50, 8)), 16, key -> { });
        cache.get(new DemTileKey(50, 8));
        assertThat(cache.estimatedSizeBytes()).isEqualTo(1024);

        cache.invalidateAll();

        assertThat(cache.estimatedSizeBytes()).isZero();
        assertThat(cache.statistics().entries()).isZero();
    }

    @Test
    void theCacheReportsItselfToTheManager() {
        CacheManager manager = new CacheManager(64);
        DemCache cache = manager.register(new DemCache(new FakeReader(new DemTileKey(50, 8)), 16, key -> { }));
        cache.get(new DemTileKey(50, 8));

        assertThat(manager.statistics()).extracting(CacheStatistics::name).contains(DemCache.NAME);
        assertThat(manager.estimatedTotalBytes()).isEqualTo(1024);
        assertThat(manager.overBudget()).isFalse();
    }

    @Test
    void tileAtResolvesTheCellContainingThePoint() {
        DemCache cache = new DemCache(new FakeReader(new DemTileKey(50, 8)), 16, key -> { });

        assertThat(cache.tileAt(50.7, 8.3)).isPresent();
        assertThat(cache.tileAt(49.7, 8.3)).isEmpty();
    }
}
