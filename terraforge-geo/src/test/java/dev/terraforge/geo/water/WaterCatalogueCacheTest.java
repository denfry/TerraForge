package dev.terraforge.geo.water;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.data.WaterProvider;
import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.geo.database.GeoDatabaseSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;

class WaterCatalogueCacheTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @TempDir
    Path temporaryDirectory;

    private static final List<LazySqliteWaterProvider.CatalogEntry> ENTRIES = List.of(
            new LazySqliteWaterProvider.CatalogEntry(1, WaterType.OCEAN, new Envelope(-10, 10, -5, 5), 0.0, 30.0, 0.0),
            new LazySqliteWaterProvider.CatalogEntry(7, WaterType.LAKE, new Envelope(20, 22, 40, 41),
                    ElevationProvider.NO_DATA, 12.5, 0.0),
            new LazySqliteWaterProvider.CatalogEntry(9, WaterType.RIVER, new Envelope(1, 2, 3, 4), 0.0, 0.0, 55.25));

    private static final WaterCatalogueCache.Key KEY = new WaterCatalogueCache.Key("/srv/terraforge.db", 4096, 1234,
            Double.doubleToLongBits(40.0));

    @Test
    void aSavedCatalogueReadsBackEntryForEntry() {
        WaterCatalogueCache cache = new WaterCatalogueCache(temporaryDirectory);
        cache.save(KEY, ENTRIES);

        assertThat(cache.load(KEY)).contains(ENTRIES);
    }

    @Test
    void aCatalogueBuiltFromAnotherDatabaseFileOrThresholdIsNotReused() {
        WaterCatalogueCache cache = new WaterCatalogueCache(temporaryDirectory);
        cache.save(KEY, ENTRIES);

        assertThat(cache.load(new WaterCatalogueCache.Key(KEY.database(), 4097, 1234, KEY.thresholdBits()))).isEmpty();
        assertThat(cache.load(new WaterCatalogueCache.Key(KEY.database(), 4096, 1235, KEY.thresholdBits()))).isEmpty();
        assertThat(cache.load(new WaterCatalogueCache.Key("/srv/other.db", 4096, 1234, KEY.thresholdBits()))).isEmpty();
        assertThat(cache.load(new WaterCatalogueCache.Key(KEY.database(), 4096, 1234,
                Double.doubleToLongBits(1.0)))).isEmpty();
    }

    @Test
    void aTruncatedCorruptedOrForeignFileIsIgnoredRatherThanTrusted() throws Exception {
        WaterCatalogueCache cache = new WaterCatalogueCache(temporaryDirectory);
        cache.save(KEY, ENTRIES);
        byte[] good = Files.readAllBytes(cache.file());

        Files.write(cache.file(), java.util.Arrays.copyOf(good, good.length - 3));
        assertThat(cache.load(KEY)).as("truncated").isEmpty();

        byte[] flipped = good.clone();
        flipped[flipped.length - 20] ^= 0x40; // inside the last entry's doubles
        Files.write(cache.file(), flipped);
        assertThat(cache.load(KEY)).as("bit flip caught by the checksum").isEmpty();

        Files.write(cache.file(), "not a catalogue at all".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(cache.load(KEY)).as("garbage").isEmpty();

        Files.write(cache.file(), new byte[0]);
        assertThat(cache.load(KEY)).as("empty").isEmpty();
    }

    @Test
    void aCachedCatalogueAnswersExactlyAsTheScanDid() throws Exception {
        Path database = createDatabase();
        insertWaterBody(database, "LAKE", 10, 20, 12, 22, null);
        insertWaterBody(database, "RIVER", 30, 30, 31, 31, 100.0);
        insertWaterBody(database, "RIVER", 40, 40, 41, 41, 1.0);
        Path cacheDirectory = temporaryDirectory.resolve("cache");

        WaterProvider first = SqliteWaterProvider.load(database, new CacheManager(64), 64, 40.0, cacheDirectory);
        Path saved = cacheDirectory.resolve(WaterCatalogueCache.FILE_NAME);
        assertThat(saved).isRegularFile();
        WaterCatalogueCache.Key key = WaterCatalogueCache.Key.of(database, 40.0);
        List<LazySqliteWaterProvider.CatalogEntry> scanned = new WaterCatalogueCache(cacheDirectory).load(key).orElseThrow();
        assertThat(scanned).extracting(LazySqliteWaterProvider.CatalogEntry::type)
                .containsExactlyInAnyOrder(WaterType.LAKE, WaterType.RIVER);

        WaterProvider second = SqliteWaterProvider.load(database, new CacheManager(64), 64, 40.0, cacheDirectory);
        try {
            for (double[] point : new double[][]{{11, 21}, {30.5, 30.5}, {40.5, 40.5}, {0, 0}}) {
                assertThat(second.waterTypeAt(point[0], point[1]))
                        .as("%s,%s", point[0], point[1])
                        .isEqualTo(first.waterTypeAt(point[0], point[1]));
            }
            assertThat(second.waterTypeAt(40.5, 40.5)).as("below the river threshold").isEqualTo(WaterType.NONE);
        } finally {
            close(first);
            close(second);
        }
    }

    @Test
    void aMatchingCacheIsReadInsteadOfScanningTheDatabase() throws Exception {
        Path database = createDatabase();
        insertWaterBody(database, "LAKE", 10, 20, 12, 22, null);
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        close(SqliteWaterProvider.load(database, new CacheManager(64), 64, 0.0, cacheDirectory));

        // Empty the table but keep the file's size and time: SQLite frees pages without shrinking the
        // file, so the key still matches. A scan would now find nothing and return null; only a
        // catalogue read from the cache file can still describe the lake.
        long size = Files.size(database);
        FileTime modified = Files.getLastModifiedTime(database);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().executeUpdate("DELETE FROM water_bodies");
        }
        Files.setLastModifiedTime(database, modified);
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.size(database) == size, "SQLite shrank the file");

        WaterProvider provider = SqliteWaterProvider.load(database, new CacheManager(64), 64, 0.0, cacheDirectory);
        assertThat(provider).isNotNull();
        close(provider);
    }

    @Test
    void aChangedDatabaseIsRescannedAndTheCacheRewritten() throws Exception {
        Path database = createDatabase();
        insertWaterBody(database, "LAKE", 10, 20, 12, 22, null);
        Path cacheDirectory = temporaryDirectory.resolve("cache");
        close(SqliteWaterProvider.load(database, new CacheManager(64), 64, 0.0, cacheDirectory));

        insertWaterBody(database, "LAKE", 50, 50, 52, 52, null);
        Files.setLastModifiedTime(database, FileTime.fromMillis(Files.getLastModifiedTime(database).toMillis() + 5_000));

        WaterProvider provider = SqliteWaterProvider.load(database, new CacheManager(64), 64, 0.0, cacheDirectory);
        try {
            assertThat(provider.waterTypeAt(51, 51)).isEqualTo(WaterType.LAKE);
        } finally {
            close(provider);
        }
        assertThat(new WaterCatalogueCache(cacheDirectory).load(WaterCatalogueCache.Key.of(database, 0.0)))
                .hasValueSatisfying(entries -> assertThat(entries).hasSize(2));
    }

    @Test
    void aCorruptCacheFileFallsBackToTheDatabaseAndIsRepaired() throws Exception {
        Path database = createDatabase();
        insertWaterBody(database, "LAKE", 10, 20, 12, 22, null);
        Path cacheDirectory = Files.createDirectories(temporaryDirectory.resolve("cache"));
        Files.write(cacheDirectory.resolve(WaterCatalogueCache.FILE_NAME), new byte[]{1, 2, 3});

        WaterProvider provider = SqliteWaterProvider.load(database, new CacheManager(64), 64, 0.0, cacheDirectory);
        try {
            assertThat(provider.waterTypeAt(11, 21)).isEqualTo(WaterType.LAKE);
        } finally {
            close(provider);
        }
        assertThat(new WaterCatalogueCache(cacheDirectory).load(WaterCatalogueCache.Key.of(database, 0.0))).isPresent();
    }

    private Path createDatabase() throws Exception {
        Path database = temporaryDirectory.resolve("terraforge.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            GeoDatabaseSchema.install(connection);
        }
        return database;
    }

    private void insertWaterBody(Path database, String waterType, double minLatitude, double minLongitude,
                                 double maxLatitude, double maxLongitude, Double discharge) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement("INSERT INTO water_bodies "
                     + "(water_type, min_lat, min_lon, max_lat, max_lon, discharge_cms, geometry) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, waterType);
            statement.setDouble(2, minLatitude);
            statement.setDouble(3, minLongitude);
            statement.setDouble(4, maxLatitude);
            statement.setDouble(5, maxLongitude);
            // NOT NULL in the schema: lakes and oceans carry 0.
            statement.setDouble(6, discharge == null ? 0.0 : discharge);
            statement.setBytes(7, new WKBWriter().write(GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                    new Coordinate(minLongitude, minLatitude), new Coordinate(maxLongitude, minLatitude),
                    new Coordinate(maxLongitude, maxLatitude), new Coordinate(minLongitude, maxLatitude),
                    new Coordinate(minLongitude, minLatitude)
            })));
            statement.executeUpdate();
        }
    }

    private static void close(WaterProvider provider) throws Exception {
        if (provider instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
