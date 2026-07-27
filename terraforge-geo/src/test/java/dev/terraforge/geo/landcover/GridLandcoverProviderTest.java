package dev.terraforge.geo.landcover;

import static org.assertj.core.api.Assertions.assertThat;
import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import org.junit.jupiter.api.Test;

class GridLandcoverProviderTest {
    @Test void samplesNorthWestRasterOrderAndFallsBackOutsideCoverage() {
        var provider = new GridLandcoverProvider(55, 37, 56, 38, 2, 2, new LandcoverClass[]{
                LandcoverClass.TREE_COVER, LandcoverClass.GRASSLAND,
                LandcoverClass.BARE_SPARSE, LandcoverClass.SNOW_ICE});
        assertThat(provider.landcoverAt(55.9, 37.1)).isEqualTo(LandcoverClass.TREE_COVER);
        assertThat(provider.landcoverAt(55.1, 37.9)).isEqualTo(LandcoverClass.SNOW_ICE);
        assertThat(provider.landcoverAt(54.9, 37.5)).isEqualTo(LandcoverClass.UNKNOWN);
    }
}
