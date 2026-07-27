package dev.terraforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.geo.landcover.LandcoverGridFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrepareLandcoverCommandTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void convertsWorldCoverCodesAndNoDataFromAsciiGrid() throws Exception {
        Path input = temporaryDirectory.resolve("worldcover.asc");
        Files.writeString(input, """
                ncols 2
                nrows 2
                xllcorner 37
                yllcorner 55
                cellsize 0.5
                NODATA_value -9999
                10 50
                90 -9999
                """);
        Path output = temporaryDirectory.resolve("prepared/region.tflc");

        PrepareLandcoverCommand command = new PrepareLandcoverCommand();
        command.input = input;
        command.output = output;

        assertThat(command.call()).isZero();
        var provider = LandcoverGridFile.read(output);
        assertThat(provider.landcoverAt(55.9, 37.1)).isEqualTo(LandcoverClass.TREE_COVER);
        assertThat(provider.landcoverAt(55.9, 37.9)).isEqualTo(LandcoverClass.BUILT_UP);
        assertThat(provider.landcoverAt(55.1, 37.1)).isEqualTo(LandcoverClass.HERBACEOUS_WETLAND);
        assertThat(provider.landcoverAt(55.1, 37.9)).isEqualTo(LandcoverClass.UNKNOWN);
    }

    @Test
    void preservesAnExistingPreparedGridWithoutExplicitOverwrite() throws Exception {
        Path input = temporaryDirectory.resolve("worldcover.asc");
        Files.writeString(input, """
                ncols 1
                nrows 1
                xllcorner 37
                yllcorner 55
                cellsize 1
                10
                """);
        Path output = temporaryDirectory.resolve("region.tflc");
        Files.writeString(output, "do not replace");

        PrepareLandcoverCommand command = new PrepareLandcoverCommand();
        command.input = input;
        command.output = output;

        assertThat(command.call()).isEqualTo(65);
        assertThat(Files.readString(output)).isEqualTo("do not replace");
    }
}
