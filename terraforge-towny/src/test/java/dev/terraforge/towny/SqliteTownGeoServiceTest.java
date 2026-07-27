package dev.terraforge.towny;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.projection.ProjectionRegistry;
import dev.terraforge.geo.database.GeoDatabaseSchema;
import dev.terraforge.geo.database.SqliteBoundaryIndex;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;

/**
 * End-to-end check of the Towny bridge: a town position in, a row in the prepared database out.
 *
 * <p>Towny's {@code Town} is deliberately absent here. Its static initialiser needs a running
 * server, so it cannot be constructed or even mocked in a unit test -- which is precisely why the
 * service exposes {@code snapshotAt}: everything except the two Towny getters is server-free and
 * therefore testable.
 */
class SqliteTownGeoServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final GeometryFactory GEOMETRIES = new GeometryFactory();

    /** Inside the prepared test country, so the origin block resolves to it. */
    private static final GeoPoint ORIGIN = GeoPoint.of(11.0, 21.0);

    @TempDir
    Path temporaryDirectory;

    private Path database;
    private CoordinateTransformer transformer;
    private SqliteBoundaryIndex geography;
    private List<String> errors;

    @BeforeEach
    void prepareGeography() throws Exception {
        database = temporaryDirectory.resolve("terraforge.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            GeoDatabaseSchema.install(connection);
            insertCountry(connection);
            insertRegion(connection);
        }
        geography = SqliteBoundaryIndex.load(database);
        transformer = new CoordinateTransformer(
                new ProjectionRegistry().create("equirectangular", ORIGIN.latitude()), ORIGIN, 1.0);
        errors = new ArrayList<>();
    }

    private SqliteTownGeoService service(double elevationMeters) {
        return new SqliteTownGeoService(transformer, constantElevation(elevationMeters), geography,
                database, errors::add);
    }

    private static ElevationProvider constantElevation(double meters) {
        return new ElevationProvider() {
            @Override
            public double elevationAt(double latitude, double longitude) {
                return meters;
            }

            @Override
            public boolean hasCoverage(double latitude, double longitude) {
                return !Double.isNaN(meters);
            }

            @Override
            public GeoBounds coverage() {
                return GeoBounds.world();
            }

            @Override
            public boolean hasBathymetry() {
                return false;
            }
        };
    }

    @Test
    void resolvesPositionCountryAndRegionFromTheTownSpawn() {
        try (SqliteTownGeoService service = service(120.0)) {
            TownGeography snapshot = service.snapshotAt(UUID.randomUUID(), "Berlin", 0, 0);

            assertThat(snapshot.latitude()).isCloseTo(ORIGIN.latitude(), Offset.offset(1e-9));
            assertThat(snapshot.longitude()).isCloseTo(ORIGIN.longitude(), Offset.offset(1e-9));
            assertThat(snapshot.elevation()).isEqualTo(120.0);
            assertThat(snapshot.country()).isPresent();
            assertThat(snapshot.region()).isPresent();
        }
    }

    @Test
    void writesTheAnnotationThroughToTheDatabase() throws Exception {
        UUID id = UUID.randomUUID();
        try (SqliteTownGeoService service = service(120.0)) {
            service.refreshAt(id, "Berlin", 0, 0);
            assertThat(service.awaitIdle(TIMEOUT)).isTrue();
        }

        Row stored = row(id);
        assertThat(stored).isNotNull();
        assertThat(stored.name()).isEqualTo("Berlin");
        assertThat(stored.latitude()).isCloseTo(ORIGIN.latitude(), Offset.offset(1e-6));
        assertThat(stored.elevation()).isEqualTo(120.0);
        assertThat(stored.countryId()).isNotNull();
        assertThat(errors).isEmpty();
    }

    @Test
    void aTownOutsideEveryBoundaryIsStillAnnotated() throws Exception {
        UUID id = UUID.randomUUID();
        try (SqliteTownGeoService service = service(0.0)) {
            // Far enough west to leave the prepared country entirely.
            service.refreshAt(id, "Nowhere", -1_000, 0);
            assertThat(service.awaitIdle(TIMEOUT)).isTrue();
        }

        Row stored = row(id);
        assertThat(stored).isNotNull();
        assertThat(stored.countryId()).isNull();
    }

    @Test
    void anUnknownElevationIsStoredAsNullRatherThanZero() throws Exception {
        UUID id = UUID.randomUUID();
        try (SqliteTownGeoService service = service(ElevationProvider.NO_DATA)) {
            service.refreshAt(id, "No DEM", 0, 0);
            assertThat(service.awaitIdle(TIMEOUT)).isTrue();
        }

        assertThat(row(id).elevation()).isNull();
    }

    @Test
    void aMovedSpawnOverwritesTheStoredPositionInsteadOfAddingARow() throws Exception {
        UUID id = UUID.randomUUID();
        try (SqliteTownGeoService service = service(0.0)) {
            service.refreshAt(id, "Berlin", 0, 0);
            service.refreshAt(id, "Berlin", 100, 100);
            assertThat(service.awaitIdle(TIMEOUT)).isTrue();
        }

        assertThat(row(id).latitude()).isNotEqualTo(ORIGIN.latitude());
        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void deletingATownRemovesItsAnnotation() throws Exception {
        UUID id = UUID.randomUUID();
        try (SqliteTownGeoService service = service(0.0)) {
            service.refreshAt(id, "Doomed", 0, 0);
            service.forget(id);
            assertThat(service.awaitIdle(TIMEOUT)).isTrue();
        }

        assertThat(row(id)).isNull();
    }

    @Test
    void aReloadedBoundaryIndexIsUsedForSubsequentLookups() throws Exception {
        try (SqliteTownGeoService service = service(0.0)) {
            service.useGeography(SqliteBoundaryIndex.load(database));

            assertThat(service.snapshotAt(UUID.randomUUID(), "Berlin", 0, 0).country()).isPresent();
        }
    }

    // --- helpers ------------------------------------------------------------

    private Row row(UUID id) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT town_name, latitude, longitude, elevation, country_id"
                             + " FROM town_geography WHERE town_uuid = ?")) {
            statement.setString(1, id.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                double elevation = rows.getDouble("elevation");
                Double storedElevation = rows.wasNull() ? null : elevation;
                int country = rows.getInt("country_id");
                Integer storedCountry = rows.wasNull() ? null : country;
                return new Row(rows.getString("town_name"), rows.getDouble("latitude"),
                        rows.getDouble("longitude"), storedElevation, storedCountry);
            }
        }
    }

    private int countRows() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM town_geography")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static void insertCountry(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO countries (id, iso_code, name, min_lat, min_lon, max_lat, max_lon, geometry)"
                        + " VALUES (1, 'TT', 'Testland', 10, 20, 12, 22, ?)")) {
            statement.setBytes(1, polygon());
            statement.executeUpdate();
        }
    }

    private static void insertRegion(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO regions (id, country_id, name, min_lat, min_lon, max_lat, max_lon, geometry)"
                        + " VALUES (1, 1, 'Test region', 10, 20, 12, 22, ?)")) {
            statement.setBytes(1, polygon());
            statement.executeUpdate();
        }
    }

    private static byte[] polygon() {
        return new WKBWriter().write(GEOMETRIES.createPolygon(new Coordinate[]{
                new Coordinate(20, 10), new Coordinate(22, 10),
                new Coordinate(22, 12), new Coordinate(20, 12), new Coordinate(20, 10)}));
    }

    private record Row(String name, double latitude, double longitude, Double elevation, Integer countryId) { }
}
