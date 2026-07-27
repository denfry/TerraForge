package dev.terraforge.geo.water;

import static org.assertj.core.api.Assertions.assertThat;

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
    void loadsNaturalWaterBodiesOnceFromThePreparedDatabase() throws Exception {
        Path database = createDatabase();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO water_bodies (water_type, geometry) VALUES (?, ?)")) {
                statement.setString(1, "LAKE");
                statement.setBytes(2, new WKBWriter().write(GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                        new Coordinate(20, 10), new Coordinate(22, 10), new Coordinate(22, 12),
                        new Coordinate(20, 12), new Coordinate(20, 10)
                })));
                statement.executeUpdate();
            }
        }

        IndexedWaterProvider provider = SqliteWaterProvider.load(database);

        assertThat(provider.waterTypeAt(11, 21)).isEqualTo(WaterType.LAKE);
        assertThat(provider.waterTypeAt(13, 21)).isEqualTo(WaterType.NONE);
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
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE water_bodies (water_type TEXT NOT NULL, geometry BLOB NOT NULL)");
        }
        return database;
    }
}
