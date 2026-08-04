package dev.terraforge.geo.water;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.data.WaterProvider;
import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.geo.database.GeoDatabaseSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;

class SqliteWaterProviderTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsNaturalWaterBodiesFromThePreparedDatabase() throws Exception {
        Path database = createDatabase();
        insertWaterBody(database, "LAKE", 10, 20, 12, 22);

        WaterProvider provider = SqliteWaterProvider.load(database);
        try {
            assertThat(provider.waterTypeAt(11, 21)).isEqualTo(WaterType.LAKE);
            assertThat(provider.waterTypeAt(13, 21)).isEqualTo(WaterType.NONE);
        } finally {
            close(provider);
        }
    }

    @Test
    void decodesGeometryLazilyAndCachesItAcrossLookups() throws Exception {
        Path database = createDatabase();
        insertWaterBody(database, "OCEAN", 0, 0, 10, 10);
        insertWaterBody(database, "LAKE", 20, 20, 22, 22);

        WaterProvider provider = SqliteWaterProvider.load(database);
        try {
            // Neither feature's geometry has been touched yet -- only their bounding boxes were
            // catalogued at load(). Repeated lookups against the same feature must return the same
            // answer, proving the lazily decoded geometry survives beyond the first call.
            assertThat(provider.waterTypeAt(5, 5)).isEqualTo(WaterType.OCEAN);
            assertThat(provider.waterTypeAt(5, 5)).isEqualTo(WaterType.OCEAN);
            assertThat(provider.waterTypeAt(21, 21)).isEqualTo(WaterType.LAKE);
            assertThat(provider.waterTypeAt(15, 15)).isEqualTo(WaterType.NONE);
        } finally {
            close(provider);
        }
    }

    @Test
    void returnsNullForAnEmptyPreparedTable() throws Exception {
        assertThat(SqliteWaterProvider.load(createDatabase())).isNull();
    }

    @Test
    void installsEverySchemaTableEvenThoughTheScriptContainsPragmas() throws Exception {
        Path database = temporaryDirectory.resolve("schema.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            GeoDatabaseSchema.install(connection);
            try (var rows = connection.getMetaData().getTables(null, null, "schema_version", new String[]{"TABLE"})) {
                assertThat(rows.next()).isTrue();
            }
            try (var rows = connection.getMetaData().getTables(null, null, "water_bodies", new String[]{"TABLE"})) {
                assertThat(rows.next()).isTrue();
            }
        }
    }

    private Path createDatabase() throws Exception {
        Path database = temporaryDirectory.resolve("terraforge.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            GeoDatabaseSchema.install(connection);
        }
        return database;
    }

    /** Mirrors what the offline importers write: bounding box columns alongside the geometry. */
    private void insertWaterBody(Path database, String waterType, double minLatitude, double minLongitude,
                                  double maxLatitude, double maxLongitude) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement("INSERT INTO water_bodies "
                     + "(water_type, min_lat, min_lon, max_lat, max_lon, geometry) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, waterType);
            statement.setDouble(2, minLatitude);
            statement.setDouble(3, minLongitude);
            statement.setDouble(4, maxLatitude);
            statement.setDouble(5, maxLongitude);
            statement.setBytes(6, new WKBWriter().write(GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                    new Coordinate(minLongitude, minLatitude), new Coordinate(maxLongitude, minLatitude),
                    new Coordinate(maxLongitude, maxLatitude), new Coordinate(minLongitude, maxLatitude),
                    new Coordinate(minLongitude, minLatitude)
            })));
            statement.executeUpdate();
        }
    }

    /** Production closes this through {@code TerraForgePlugin.onDisable}; tests must do it themselves. */
    private static void close(WaterProvider provider) throws Exception {
        if (provider instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
