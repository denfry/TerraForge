package dev.terraforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.config.ConfigLoader;
import dev.terraforge.geo.dem.DemTileKey;
import dev.terraforge.geo.dem.FileDemReader;
import dev.terraforge.geo.database.SqliteBoundaryIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole command, run offline against a prepared source tree.
 *
 * <p>{@code --offline} is what makes this testable without a network: every step except the download
 * is the same code the online run executes.
 */
class SetupCommandTest {

    @TempDir Path temporaryDirectory;

    @Test
    void configuresPreparesAndValidatesInOnePass() throws Exception {
        Path sources = temporaryDirectory.resolve("source-data");
        writeHgtTile(sources.resolve("dem/N47E008.hgt"));
        Files.createDirectories(sources.resolve("boundaries"));
        Files.writeString(sources.resolve("boundaries/countries.geojson"), """
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"NAME":"Switzerland","ISO_A2":"CH"},
                  "geometry":{"type":"Polygon","coordinates":[[[8,47],[9,47],[9,48],[8,48],[8,47]]]}}
                ]}
                """);
        Path plugin = temporaryDirectory.resolve("plugins/TerraForge");

        assertThat(command(plugin, sources).call()).isZero();

        var config = new ConfigLoader().load(plugin.resolve("terraforge.yml"));
        assertThat(config.earth().origin().latitude()).isEqualTo(47.5);
        try (var reader = FileDemReader.open(plugin.resolve("data/dem"))) {
            assertThat(reader.exists(new DemTileKey(47, 8))).isTrue();
        }
        assertThat(SqliteBoundaryIndex.load(plugin.resolve("terraforge.db")).countryAt(47.5, 8.5))
                .map(country -> country.isoCode()).contains("CH");
    }

    @Test
    void reportsAnEmptySourceTreeInsteadOfPretendingItPreparedAWorld() throws Exception {
        Path sources = temporaryDirectory.resolve("source-data");
        Files.createDirectories(sources);
        Path plugin = temporaryDirectory.resolve("plugins/TerraForge");

        assertThat(command(plugin, sources).call()).isEqualTo(65);
        // The configuration is still written: it is the part that does not depend on the data.
        assertThat(plugin.resolve("terraforge.yml")).exists();
    }

    /** A one-degree SRTM tile of flat ground: 1201 by 1201 big-endian signed shorts. */
    private void writeHgtTile(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        byte[] samples = new byte[1201 * 1201 * 2];
        for (int i = 0; i < samples.length; i += 2) {
            samples[i] = 0x01;
            samples[i + 1] = 0x2C; // 300 metres
        }
        Files.write(file, samples);
    }

    private SetupCommand command(Path output, Path sources) {
        SetupCommand command = new SetupCommand();
        command.region = new RegionOptions();
        command.region.latMin = 47.0;
        command.region.latMax = 48.0;
        command.region.lonMin = 8.0;
        command.region.lonMax = 9.0;
        command.output = output;
        command.sourceData = sources;
        command.world = "earth";
        command.scale = 1.0;
        command.regionName = "prepared-region";
        command.demResolution = "90";
        command.cities = "cities15000";
        command.encoding = "int16";
        command.samplesPerDegree = 600;
        command.offline = true;
        return command;
    }
}
