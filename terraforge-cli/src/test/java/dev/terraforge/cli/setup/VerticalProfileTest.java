package dev.terraforge.cli.setup;

import dev.terraforge.core.config.VerticalProfile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.terrain.VerticalScale;
import org.junit.jupiter.api.Test;

class VerticalProfileTest {

    private static final double EVEREST = 8849.0;
    private static final double ABYSSAL_PLAIN = -4000.0;

    /**
     * The finding this whole profile exists for: the shipped default cannot show a mountain. At one
     * metre per block the soft clamp starts 249 m above sea level, so the Alps, the Andes and the
     * Himalaya all generate as the same plateau.
     */
    @Test
    void theRegionalDefaultCannotRepresentAMountainRange() {
        VerticalScale scale = scaleOf(VerticalProfile.regional());

        assertThat(scale.highestUncompressedElevation()).isLessThan(300.0);
        assertThat(scale.flattensRealTerrain()).isTrue();
        assertThat(scale.toBlockY(EVEREST)).isEqualTo(scale.toBlockY(3000.0));
    }

    @Test
    void thePlanetProfileKeepsEverestAndTheAbyssalPlainInTrueShape() {
        VerticalProfile profile = VerticalProfile.planet();
        VerticalScale scale = scaleOf(profile);

        assertThat(scale.flattensRealTerrain()).isFalse();
        assertThat(scale.highestUncompressedElevation()).isGreaterThan(EVEREST);
        assertThat(scale.deepestUncompressedElevation()).isLessThan(ABYSSAL_PLAIN);
        // Distinct heights stay distinct -- the property the regional default loses.
        assertThat(scale.toBlockY(EVEREST)).isGreaterThan(scale.toBlockY(6000.0));
        assertThat(scale.toBlockY(6000.0)).isGreaterThan(scale.toBlockY(3000.0));
    }

    @Test
    void thePlanetProfileCostsWhatTheDocumentationSays() {
        VerticalProfile profile = VerticalProfile.planet();

        assertThat(profile.height()).isEqualTo(1024);
        assertThat(profile.chunkSections()).isEqualTo(64);
        assertThat(profile.everestBlocks()).isEqualTo(442);
        assertThat(profile.needsDatapack()).isTrue();
    }

    /** One trench is compressed, and it stays the deepest place in the world. */
    @Test
    void theMarianaTrenchIsCompressedButStillTheDeepestPoint() {
        VerticalScale scale = scaleOf(VerticalProfile.planet());

        int mariana = scale.toBlockY(-10935.0);
        int abyssal = scale.toBlockY(ABYSSAL_PLAIN);

        assertThat(mariana).isLessThan(abyssal);
        assertThat(mariana).isGreaterThan(scale.minY());
    }

    @Test
    void aPlanetWideBoxGetsThePlanetProfileAndARegionalOneDoesNot() {
        assertThat(VerticalProfile.forBounds(GeoBounds.world()))
                .isEqualTo(VerticalProfile.planet());
        assertThat(VerticalProfile.forBounds(new GeoBounds(47.0, 5.0, 55.5, 15.5)))
                .isEqualTo(VerticalProfile.regional());
    }

    @Test
    void refusesAWorldTallerThanMinecraftAllows() {
        assertThatThrownBy(() -> new VerticalProfile(0, -3000, 2032, 20.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Minecraft allows world heights");
    }

    @Test
    void refusesAHeightThatIsNotAWholeNumberOfSections() {
        assertThatThrownBy(() -> new VerticalProfile(0, -512, 500, 20.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiple of 16");
    }

    @Test
    void refusesASeaLevelOutsideTheWorld() {
        assertThatThrownBy(() -> new VerticalProfile(600, -512, 512, 20.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sea-level");
    }

    /** Vanilla height needs no datapack; anything else does, and saying so is the whole point. */
    @Test
    void onlyANonVanillaHeightNeedsADatapack() {
        assertThat(VerticalProfile.regional().needsDatapack()).isFalse();
        assertThat(new VerticalProfile(0, -512, 512, 20.0).needsDatapack()).isTrue();
    }

    private static VerticalScale scaleOf(VerticalProfile profile) {
        return new VerticalScale(profile.seaLevel(), profile.minY(), profile.maxY(),
                1.0, profile.metersPerBlock());
    }
}
