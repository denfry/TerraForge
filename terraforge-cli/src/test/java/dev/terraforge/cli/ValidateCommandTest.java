package dev.terraforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.geo.database.GeoDatabaseSchema;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;

class ValidateCommandTest {

    private static final GeometryFactory GEOMETRIES = new GeometryFactory();

    @TempDir
    Path temporaryDirectory;

    private Path database;

    @BeforeEach
    void createSchema() throws Exception {
        database = temporaryDirectory.resolve("terraforge.db");
        try (Connection connection = open()) {
            GeoDatabaseSchema.install(connection);
        }
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private ValidateCommand command() {
        ValidateCommand command = new ValidateCommand();
        command.database = database;
        return command;
    }

    private static String output(Supplier<Integer> run, int expectedExitCode) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            assertThat(run.get()).isEqualTo(expectedExitCode);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private void insertCountry(int id, String iso, String name,
                               double south, double west, double north, double east) throws Exception {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO countries (id, iso_code, name, min_lat, min_lon, max_lat, max_lon, geometry)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, iso);
            statement.setString(3, name);
            statement.setDouble(4, south);
            statement.setDouble(5, west);
            statement.setDouble(6, north);
            statement.setDouble(7, east);
            statement.setBytes(8, polygon(south, west, north, east));
            statement.executeUpdate();
        }
    }

    private void insertRegion(int id, int countryId, String name,
                              double south, double west, double north, double east) throws Exception {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO regions (id, country_id, name, min_lat, min_lon, max_lat, max_lon, geometry)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setInt(2, countryId);
            statement.setString(3, name);
            statement.setDouble(4, south);
            statement.setDouble(5, west);
            statement.setDouble(6, north);
            statement.setDouble(7, east);
            statement.setBytes(8, polygon(south, west, north, east));
            statement.executeUpdate();
        }
    }

    private void insertCity(String name, double latitude, double longitude, int countryId, boolean capital)
            throws Exception {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO cities (name, latitude, longitude, country_id, capital) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, name);
            statement.setDouble(2, latitude);
            statement.setDouble(3, longitude);
            statement.setInt(4, countryId);
            statement.setInt(5, capital ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private static byte[] polygon(double south, double west, double north, double east) {
        return new WKBWriter().write(GEOMETRIES.createPolygon(new Coordinate[]{
                new Coordinate(west, south), new Coordinate(east, south),
                new Coordinate(east, north), new Coordinate(west, north), new Coordinate(west, south)}));
    }

    @Test
    void acceptsAConsistentDatabase() throws Exception {
        insertCountry(1, "TT", "Testland", 10, 20, 12, 22);
        insertRegion(1, 1, "Test region", 10, 20, 11, 21);
        insertCity("Capital", 11, 21, 1, true);

        assertThat(output(command()::call, 0)).contains("No problems found.");
    }

    @Test
    void reportsCoverageAndCounts() throws Exception {
        insertCountry(1, "TT", "Testland", 10, 20, 12, 22);
        insertCity("Capital", 11, 21, 1, true);

        String report = output(command()::call, 0);

        assertThat(report).contains("Countries: 1");
        assertThat(report).contains("Cities:    1 (1 capitals)");
        assertThat(report).contains("Coverage:  10.0000..12.0000 N, 20.0000..22.0000 E");
    }

    @Test
    void aDuplicateCountryNameIsAWarningBecauseLookupsBecomeAmbiguous() throws Exception {
        insertCountry(1, "AA", "Testland", 10, 20, 12, 22);
        insertCountry(2, "BB", "Testland", 30, 40, 32, 42);

        String report = output(command()::call, 0);

        assertThat(report).contains("Country name 'testland' appears 2 times");
    }

    @Test
    void overlappingCountriesAreAnError() throws Exception {
        insertCountry(1, "AA", "Alpha", 10, 20, 12, 22);
        insertCountry(2, "BB", "Beta", 10.5, 20.5, 12, 22);

        String report = output(command()::call, 65);

        assertThat(report).contains("Alpha and Beta overlap by");
    }

    @Test
    void aSharedBorderIsNotReportedAsAnOverlap() throws Exception {
        insertCountry(1, "AA", "Alpha", 10, 20, 12, 22);
        insertCountry(2, "BB", "Beta", 10, 22, 12, 24);

        assertThat(output(command()::call, 0)).doesNotContain("overlap");
    }

    @Test
    void aRegionOutsideItsCountryIsAnError() throws Exception {
        insertCountry(1, "TT", "Testland", 10, 20, 12, 22);
        insertRegion(1, 1, "Far region", 50, 60, 51, 61);

        String report = output(command()::call, 65);

        assertThat(report).contains("Region 'Far region' lies outside its country Testland");
    }

    @Test
    void aRegionWithoutItsCountryIsAnError() throws Exception {
        insertRegion(1, 99, "Orphan", 10, 20, 11, 21);

        String report = output(command()::call, 65);

        assertThat(report).contains("1 region(s) reference a country that is not in the database");
    }

    @Test
    void aCountryWithoutACapitalIsOnlyAWarning() throws Exception {
        insertCountry(1, "TT", "Testland", 10, 20, 12, 22);

        String report = output(command()::call, 0);

        assertThat(report).contains("Warning: 1 country/countries have no capital");
    }

    @Test
    void twoCapitalsForOneCountryIsAWarning() throws Exception {
        insertCountry(1, "TT", "Testland", 10, 20, 12, 22);
        insertCity("First", 11, 21, 1, true);
        insertCity("Second", 11.5, 21.5, 1, true);

        assertThat(output(command()::call, 0)).contains("Testland has 2 capitals");
    }

    @Test
    void aCityOutsideTheCountryItIsAssignedToIsAWarning() throws Exception {
        insertCountry(1, "TT", "Testland", 10, 20, 12, 22);
        insertCity("Capital", 11, 21, 1, true);
        insertCity("Misplaced", 50, 60, 1, false);

        String report = output(command()::call, 0);

        assertThat(report).contains("Misplaced").contains("lies outside its boundary");
    }

    @Test
    void strictTurnsWarningsIntoAFailingExitCode() throws Exception {
        insertCountry(1, "TT", "Testland", 10, 20, 12, 22);
        ValidateCommand command = command();
        command.strict = true;

        // No capital: a warning, which --strict promotes to a failure.
        assertThat(command.call()).isEqualTo(65);
    }

    @Test
    void reportsAMissingDatabaseFile() {
        ValidateCommand command = new ValidateCommand();
        command.database = temporaryDirectory.resolve("absent.db");

        assertThat(command.call()).isEqualTo(66);
    }
}
