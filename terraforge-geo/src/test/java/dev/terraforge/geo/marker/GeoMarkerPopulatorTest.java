package dev.terraforge.geo.marker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.api.GeoMarkerService;
import dev.terraforge.core.api.GeoMarkerService.GeoMarker;
import dev.terraforge.core.api.GeoMarkerService.MarkerType;
import dev.terraforge.core.api.InMemoryGeoMarkerService;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.geo.database.GeoDatabaseSchema;
import dev.terraforge.geo.database.SqliteBoundaryIndex;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;

class GeoMarkerPopulatorTest {

    private static final GeometryFactory GEOMETRIES = new GeometryFactory();

    @TempDir
    Path temporaryDirectory;

    private SqliteBoundaryIndex geography;
    private GeoMarkerService markers;

    @BeforeEach
    void prepareDatabase() throws Exception {
        Path database = temporaryDirectory.resolve("terraforge.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            GeoDatabaseSchema.install(connection);
            insertCountry(connection, 1, "TT", "Testland");
            insertRegion(connection, 1, 1, "Test region");
            insertCity(connection, "Capital", 10.1, 20.1, 100_000, 1, true);
            insertCity(connection, "Big", 10.2, 20.2, 90_000, 1, false);
            insertCity(connection, "Small", 10.3, 20.3, 500, 1, false);
            insertCity(connection, "Unknown size", 10.4, 20.4, 0, 1, false);
        }
        geography = SqliteBoundaryIndex.load(database);
        markers = new InMemoryGeoMarkerService();
    }

    @Test
    void publishesCitiesCapitalsCountriesAndRegions() {
        int published = GeoMarkerPopulator.populate(markers, geography,
                GeoMarkerPopulator.Options.defaults(true, true));

        assertThat(published).isEqualTo(6);
        assertThat(markers.byType(MarkerType.CAPITAL)).extracting(GeoMarker::label).containsExactly("Capital");
        assertThat(markers.byType(MarkerType.CITY)).extracting(GeoMarker::label)
                .containsExactlyInAnyOrder("Big", "Small", "Unknown size");
        assertThat(markers.byType(MarkerType.COUNTRY)).extracting(GeoMarker::label).containsExactly("Testland");
        assertThat(markers.byType(MarkerType.REGION)).extracting(GeoMarker::label).containsExactly("Test region");
        assertThat(markers.all()).extracting(GeoMarker::id)
                .allMatch(id -> id.startsWith(GeoMarkerPopulator.NAMESPACE));
    }

    @Test
    void honoursTheConfigSwitches() {
        GeoMarkerPopulator.populate(markers, geography, GeoMarkerPopulator.Options.defaults(true, false));
        assertThat(markers.byType(MarkerType.COUNTRY)).isEmpty();
        assertThat(markers.byType(MarkerType.CITY)).isNotEmpty();

        GeoMarkerPopulator.populate(markers, geography, GeoMarkerPopulator.Options.defaults(false, true));
        assertThat(markers.byType(MarkerType.CITY)).isEmpty();
        assertThat(markers.byType(MarkerType.COUNTRY)).isNotEmpty();
    }

    @Test
    void keepsCapitalsWhateverTheCapAndPopulationFloor() {
        GeoMarkerPopulator.populate(markers, geography,
                new GeoMarkerPopulator.Options(true, false, 0, 1_000_000));

        assertThat(markers.all()).extracting(GeoMarker::label).containsExactly("Capital");
    }

    @Test
    void ranksCitiesByPopulationWhenCapped() {
        GeoMarkerPopulator.populate(markers, geography, new GeoMarkerPopulator.Options(true, false, 1, 0));

        assertThat(markers.byType(MarkerType.CITY)).extracting(GeoMarker::label).containsExactly("Big");
    }

    @Test
    void dropsCitiesOfUnknownPopulationOnceAFloorIsSet() {
        GeoMarkerPopulator.populate(markers, geography, new GeoMarkerPopulator.Options(true, false, 100, 1_000));

        assertThat(markers.byType(MarkerType.CITY)).extracting(GeoMarker::label).containsExactly("Big");
    }

    @Test
    void repopulationReplacesOwnMarkersAndKeepsThirdPartyOnes() {
        markers.register(new GeoMarker("myplugin:harbour", "Old Harbour", MarkerType.POINT_OF_INTEREST,
                GeoPoint.of(53.5459, 9.9695), "Player-built harbour"));
        GeoMarkerPopulator.populate(markers, geography, GeoMarkerPopulator.Options.defaults(true, true));

        GeoMarkerPopulator.populate(markers, geography, GeoMarkerPopulator.Options.defaults(false, false));

        assertThat(markers.all()).extracting(GeoMarker::id).containsExactly("myplugin:harbour");
    }

    @Test
    void withoutAPreparedDatabaseNothingIsPublishedAndNothingBreaks() {
        markers.register(new GeoMarker("myplugin:harbour", "Old Harbour", MarkerType.POINT_OF_INTEREST,
                GeoPoint.of(53.5459, 9.9695), null));

        assertThat(GeoMarkerPopulator.populate(markers, null, GeoMarkerPopulator.Options.defaults(true, true)))
                .isZero();
        assertThat(markers.all()).extracting(GeoMarker::id).containsExactly("myplugin:harbour");
    }

    @Test
    void cityDetailNamesTheCountryAndPopulation() {
        GeoMarkerPopulator.populate(markers, geography, GeoMarkerPopulator.Options.defaults(true, true));

        assertThat(markers.byType(MarkerType.CAPITAL)).first()
                .extracting(GeoMarker::detail).isEqualTo("Capital of Testland -- population 100,000");
        assertThat(markers.byType(MarkerType.COUNTRY)).first().extracting(GeoMarker::detail).isEqualTo("TT");
        assertThat(markers.byType(MarkerType.REGION)).first().extracting(GeoMarker::detail).isEqualTo("Testland");
    }

    private static void insertCountry(Connection connection, int id, String iso, String name) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO countries (id, iso_code, name, min_lat, min_lon, max_lat, max_lon, geometry)"
                        + " VALUES (?, ?, ?, 10, 20, 12, 22, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, iso);
            statement.setString(3, name);
            statement.setBytes(4, polygon());
            statement.executeUpdate();
        }
    }

    private static void insertRegion(Connection connection, int id, int countryId, String name) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO regions (id, country_id, name, min_lat, min_lon, max_lat, max_lon, geometry)"
                        + " VALUES (?, ?, ?, 10, 20, 12, 22, ?)")) {
            statement.setInt(1, id);
            statement.setInt(2, countryId);
            statement.setString(3, name);
            statement.setBytes(4, polygon());
            statement.executeUpdate();
        }
    }

    private static void insertCity(Connection connection, String name, double latitude, double longitude,
                                   long population, int countryId, boolean capital) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO cities (name, ascii_name, latitude, longitude, population, country_id, capital)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, name);
            statement.setString(2, name);
            statement.setDouble(3, latitude);
            statement.setDouble(4, longitude);
            statement.setLong(5, population);
            statement.setInt(6, countryId);
            statement.setInt(7, capital ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private static byte[] polygon() {
        return new WKBWriter().write(GEOMETRIES.createPolygon(new Coordinate[]{
                new Coordinate(20, 10), new Coordinate(22, 10),
                new Coordinate(22, 12), new Coordinate(20, 12), new Coordinate(20, 10)}));
    }
}
