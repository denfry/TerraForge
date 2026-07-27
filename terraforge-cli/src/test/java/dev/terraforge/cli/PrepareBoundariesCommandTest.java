package dev.terraforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.geo.database.SqliteBoundaryIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrepareBoundariesCommandTest {
    @TempDir Path temporaryDirectory;

    @Test
    void importsOnlyExplicitCountryAndRegionBoundaries() throws Exception {
        Path input = temporaryDirectory.resolve("boundaries.geojson");
        Files.writeString(input, """
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"boundary_type":"COUNTRY","name":"Testland","iso_code":"TT"},"geometry":{"type":"Polygon","coordinates":[[[20,10],[22,10],[22,12],[20,12],[20,10]]]}},
                 {"type":"Feature","properties":{"boundary_type":"REGION","name":"Test region","country_iso_code":"TT","admin_level":1},"geometry":{"type":"Polygon","coordinates":[[[20,10],[22,10],[22,12],[20,12],[20,10]]]}}
                ]}
                """);
        Path database = temporaryDirectory.resolve("terraforge.db");
        PrepareBoundariesCommand command = new PrepareBoundariesCommand();
        command.input = input; command.database = database;

        assertThat(command.call()).isZero();
        SqliteBoundaryIndex index = SqliteBoundaryIndex.load(database);
        assertThat(index.countryAt(11, 21)).map(country -> country.isoCode()).contains("TT");
        assertThat(index.regionAt(11, 21)).map(region -> region.name()).contains("Test region");
    }
}
