package dev.terraforge.core.terrain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.core.data.WaterProvider.WaterType;
import org.junit.jupiter.api.Test;

/** The Antarctic ice sheet: flat, low, and ice sheet to the water's edge. */
class AntarcticaTest {

    private final ClimateBiomeResolver resolver = new ClimateBiomeResolver();

    @Test
    void theIceSheetIsALowGentleDome() {
        // A 4 km plateau becomes a 160 m rise; the coast stands 40 m above the sea.
        assertThat(Antarctica.flatten(4_000.0)).isCloseTo(160.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(Antarctica.flatten(0.0)).isEqualTo(Antarctica.COAST_METERS);
        assertThat(Antarctica.flatten(-300.0)).isEqualTo(Antarctica.COAST_METERS); // below-sea ice, still ice
        // Monotone: the sheet still rises inland, only far more gently.
        double previous = Antarctica.flatten(0.0);
        for (double metres = 100.0; metres <= 4_500.0; metres += 100.0) {
            double flattened = Antarctica.flatten(metres);
            assertThat(flattened).isGreaterThanOrEqualTo(previous);
            assertThat(flattened).isLessThan(200.0);
            previous = flattened;
        }
    }

    @Test
    void antarcticLandIsIceSheetWhateverTheElevationOrCover() {
        for (LandcoverClass cover : LandcoverClass.values()) {
            assertThat(resolver.resolve(-75.0, 2.0, WaterType.NONE, cover)).isEqualTo(ClimateBiome.GLACIER);
            assertThat(resolver.resolve(-65.0, 120.0, WaterType.NONE, cover)).isEqualTo(ClimateBiome.GLACIER);
        }
        // Not the shore, not the tundra: the sheet meets the sea as ice.
        assertThat(resolver.resolve(-70.0, 1.0, WaterType.NONE, LandcoverClass.UNKNOWN)).isEqualTo(ClimateBiome.GLACIER);
    }

    @Test
    void theArcticAndTheSouthernOceanAreUntouched() {
        assertThat(Antarctica.isIceSheet(75.0)).isFalse();
        assertThat(Antarctica.isIceSheet(-59.9)).isFalse();
        assertThat(resolver.resolve(75.0, 50.0, WaterType.NONE, LandcoverClass.UNKNOWN)).isEqualTo(ClimateBiome.TUNDRA);
        assertThat(resolver.resolve(-75.0, -2_000.0, WaterType.OCEAN, LandcoverClass.PERMANENT_WATER))
                .isEqualTo(ClimateBiome.FROZEN_OCEAN);
    }
}
