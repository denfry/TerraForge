package dev.terraforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrepareCitiesCommandTest {
    @TempDir Path temporaryDirectory;
    @Test void importsStandardGeoNamesColumns() throws Exception {
        Path input = temporaryDirectory.resolve("cities15000.txt");
        Files.writeString(input, "1\tTest City\tTest City\t\t55.75\t37.62\tP\tPPLC\tRU\t\t\t\t\t\t1000000\t\t\t\t\n");
        Path database = temporaryDirectory.resolve("terraforge.db");
        PrepareCitiesCommand command = new PrepareCitiesCommand(); command.input = input; command.database = database;
        assertThat(command.call()).isZero();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var rows = connection.createStatement().executeQuery("SELECT name, latitude, longitude, capital FROM cities")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("Test City");
            assertThat(rows.getDouble(2)).isEqualTo(55.75); assertThat(rows.getDouble(3)).isEqualTo(37.62);
            assertThat(rows.getInt(4)).isEqualTo(1);
        }
    }

    @Test void aCityIsFoundByItsAsciiAndAlternateNames() throws Exception {
        Path input = temporaryDirectory.resolve("cities15000.txt");
        Files.writeString(input,
                "1\tMünchen\tMuenchen\tMunich,Monaco di Baviera\t48.14\t11.58\tP\tPPLA\tDE\t\t\t\t\t\t1500000\t\t\t\t\n"
                + "2\tSmall Munich\tSmall Munich\t\t10.0\t10.0\tP\tPPL\tDE\t\t\t\t\t\t100\t\t\t\t\n");
        Path database = temporaryDirectory.resolve("terraforge.db");
        PrepareCitiesCommand command = new PrepareCitiesCommand(); command.input = input; command.database = database;
        assertThat(command.call()).isZero();

        var index = dev.terraforge.geo.database.SqliteBoundaryIndex.load(database);
        assertThat(index.findCity("München")).map(city -> city.name()).contains("München");
        assertThat(index.findCity("Munich")).map(city -> city.name()).contains("München");
        assertThat(index.findCity("muenchen")).map(city -> city.name()).contains("München");
        assertThat(index.findCity("Monaco di Baviera")).map(city -> city.name()).contains("München");
        // Prefix search covers aliases too, and ranks the larger place first.
        assertThat(index.searchCities("muni", 5)).extracting(city -> city.name())
                .containsExactly("München");
        assertThat(index.findCity("Nowhere")).isEmpty();
    }
}
