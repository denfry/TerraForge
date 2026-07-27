package dev.terraforge.geo.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;

class SqliteBoundaryIndexTest {

    private static final GeometryFactory GEOMETRIES = new GeometryFactory();

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsPreparedCountriesAndRegionsIntoExactSpatialIndexes() throws Exception {
        Path database = temporaryDirectory.resolve("terraforge.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            GeoDatabaseSchema.install(connection);
            insertCountry(connection);
            insertRegion(connection);
            insertCities(connection);
        }

        SqliteBoundaryIndex index = SqliteBoundaryIndex.load(database);

        assertThat(index.countryAt(11, 21)).map(country -> country.isoCode()).contains("TT");
        assertThat(index.regionAt(11, 21)).map(region -> region.name()).contains("Test region");
        assertThat(index.countryAt(15, 21)).isEmpty();
        assertThat(index.findCountry("tt")).map(country -> country.name()).contains("Testland");
        assertThat(index.findCountry("testland")).map(country -> country.isoCode()).contains("TT");
        assertThat(index.countryCount()).isEqualTo(1);
        assertThat(index.regionCount()).isEqualTo(1);
        assertThat(index.findCountryByIsoCode("tt")).map(country -> country.name()).contains("Testland");
        assertThat(index.searchCountries("test", 5)).extracting(country -> country.isoCode()).containsExactly("TT");
        assertThat(index.findCityByName("Capital")).isPresent();
        assertThat(index.getNearestCities(10.05, 20.05, 20, 5)).extracting(city -> city.name()).containsExactly("Capital");
        assertThat(index.getCountryAnchor(index.findCountry("TT").orElseThrow()))
                .hasValueSatisfying(point -> assertThat(point.latitude()).isEqualTo(10.1));
        assertThat(index.coverage().contains(11, 21)).isTrue();
    }

    private static void insertCountry(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO countries (id, iso_code, name, min_lat, min_lon, max_lat, max_lon, geometry)
                VALUES (1, 'TT', 'Testland', 10, 20, 12, 22, ?)
                """)) {
            statement.setBytes(1, polygon(10, 20, 12, 22));
            statement.executeUpdate();
        }
    }

    private static void insertRegion(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO regions (id, country_id, name, min_lat, min_lon, max_lat, max_lon, geometry)
                VALUES (1, 1, 'Test region', 10, 20, 12, 22, ?)
                """)) {
            statement.setBytes(1, polygon(10, 20, 12, 22));
            statement.executeUpdate();
        }
    }

    private static void insertCities(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO cities (name, ascii_name, latitude, longitude, population, country_id, capital)
                VALUES ('Capital', 'Capital', 10.1, 20.1, 100000, 1, 1)
                """)) {
            statement.executeUpdate();
        }
    }

    private static byte[] polygon(double south, double west, double north, double east) {
        return new WKBWriter().write(GEOMETRIES.createPolygon(new Coordinate[]{
                new Coordinate(west, south), new Coordinate(east, south), new Coordinate(east, north),
                new Coordinate(west, north), new Coordinate(west, south)
        }));
    }
}
