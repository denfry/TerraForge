package dev.terraforge.geo.landcover;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LandcoverGridFileTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesNorthWestRasterOrderAndClassValues() throws Exception {
        Path grid = temporaryDirectory.resolve("region.tflc");
        LandcoverGridFile.write(grid, 55, 37, 56, 38, 2, 2, new LandcoverClass[]{
                LandcoverClass.TREE_COVER, LandcoverClass.GRASSLAND,
                LandcoverClass.BARE_SPARSE, LandcoverClass.SNOW_ICE
        });

        var provider = LandcoverGridFile.read(grid);

        assertThat(provider.landcoverAt(55.9, 37.1)).isEqualTo(LandcoverClass.TREE_COVER);
        assertThat(provider.landcoverAt(55.1, 37.9)).isEqualTo(LandcoverClass.SNOW_ICE);
    }
}
