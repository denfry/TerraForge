package dev.terraforge.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import org.junit.jupiter.api.Test;

class WorldCoverTest {

    @Test
    void mapsEveryPublishedWorldCoverClass() {
        assertThat(WorldCover.fromCode(10)).isEqualTo(LandcoverClass.TREE_COVER);
        assertThat(WorldCover.fromCode(40)).isEqualTo(LandcoverClass.CROPLAND);
        assertThat(WorldCover.fromCode(80)).isEqualTo(LandcoverClass.PERMANENT_WATER);
        assertThat(WorldCover.fromCode(100)).isEqualTo(LandcoverClass.MOSS_LICHEN);
    }

    @Test
    void unknownAndNoDataValuesNeverInventLandcover() {
        assertThat(WorldCover.fromCode(0)).isEqualTo(LandcoverClass.UNKNOWN);
        assertThat(WorldCover.fromCode(255)).isEqualTo(LandcoverClass.UNKNOWN);
    }
}
