package dev.terraforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.config.ConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitCommandTest {

    @TempDir Path temporaryDirectory;

    @Test
    void createsTheDirectoriesTheRuntimeReadsAndAConfigMatchingTheBox() throws Exception {
        Path plugin = temporaryDirectory.resolve("plugins/TerraForge");

        assertThat(command(plugin, 47.0, 48.0, 8.0, 9.0).call()).isZero();

        assertThat(plugin.resolve("data/dem")).isDirectory();
        assertThat(plugin.resolve("data/landcover")).isDirectory();
        assertThat(plugin.resolve("cache")).isDirectory();

        var config = new ConfigLoader().load(plugin.resolve("terraforge.yml"));
        assertThat(config.testRegion().toBounds().minLatitude()).isEqualTo(47.0);
        assertThat(config.testRegion().toBounds().maxLongitude()).isEqualTo(9.0);
        // Block 0,0 defaults to the middle of the prepared region, not to the built-in default.
        assertThat(config.earth().origin().latitude()).isEqualTo(47.5);
        assertThat(config.earth().origin().longitude()).isEqualTo(8.5);
        assertThat(config.world().name()).isEqualTo("earth");
        assertThat(config.infrastructure().anyEnabled()).isFalse();
    }

    @Test
    void honoursAnExplicitOriginAndWorldName() throws Exception {
        Path plugin = temporaryDirectory.resolve("plugins/TerraForge");
        InitCommand command = command(plugin, 47.0, 48.0, 8.0, 9.0);
        command.originLatitude = 47.37;
        command.originLongitude = 8.54;
        command.world = "terra";
        command.scale = 4.0;

        assertThat(command.call()).isZero();

        var config = new ConfigLoader().load(plugin.resolve("terraforge.yml"));
        assertThat(config.earth().origin().latitude()).isEqualTo(47.37);
        assertThat(config.world().name()).isEqualTo("terra");
        assertThat(config.scale().blocksPerKm()).isEqualTo(4.0);
    }

    @Test
    void neverSilentlyRewritesAnExistingConfiguration() throws Exception {
        Path plugin = temporaryDirectory.resolve("plugins/TerraForge");
        assertThat(command(plugin, 47.0, 48.0, 8.0, 9.0).call()).isZero();
        String original = Files.readString(plugin.resolve("terraforge.yml"));

        // A different box: the origin of a populated world must not move underneath it.
        assertThat(command(plugin, 10.0, 11.0, 20.0, 21.0).call()).isZero();

        assertThat(plugin.resolve("terraforge.yml")).hasContent(original);
    }

    @Test
    void rejectsAHalfGivenOrigin() {
        InitCommand command = command(temporaryDirectory.resolve("plugins/TerraForge"), 47.0, 48.0, 8.0, 9.0);
        command.originLatitude = 47.37;

        assertThat(command.call()).isEqualTo(64);
    }

    @Test
    void rejectsAnInsideOutBoundingBox() {
        assertThat(command(temporaryDirectory.resolve("plugins/TerraForge"), 48.0, 47.0, 8.0, 9.0).call())
                .isEqualTo(64);
    }

    private InitCommand command(Path output, double latMin, double latMax, double lonMin, double lonMax) {
        InitCommand command = new InitCommand();
        command.region = new RegionOptions();
        command.region.latMin = latMin;
        command.region.latMax = latMax;
        command.region.lonMin = lonMin;
        command.region.lonMax = lonMax;
        command.output = output;
        command.world = "earth";
        command.scale = 1.0;
        command.regionName = "prepared-region";
        return command;
    }
}
