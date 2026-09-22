package dev.terraforge.core.terrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VerticalScaleTest {

    private static VerticalScale defaultScale() {
        return new VerticalScale(63, -64, 320, 1.0, 1.0);
    }

    @Test
    void seaLevelIsZeroElevation() {
        assertThat(defaultScale().toBlockY(0.0)).isEqualTo(63);
    }

    @Test
    @DisplayName("elevation maps 1:1 at exaggeration 1.0")
    void linearMapping() {
        assertThat(defaultScale().toBlockY(100.0)).isEqualTo(163);
        assertThat(defaultScale().toBlockY(-20.0)).isEqualTo(43);
    }

    @Test
    @DisplayName("vertical exaggeration multiplies elevation")
    void exaggerationApplies() {
        VerticalScale exaggerated = new VerticalScale(63, -64, 320, 2.0, 1.0);
        assertThat(exaggerated.toBlockY(100.0)).isEqualTo(263);
    }

    @Test
    @DisplayName("meters-per-block compresses elevation")
    void metersPerBlockApplies() {
        VerticalScale compressed = new VerticalScale(63, -64, 320, 1.0, 4.0);
        assertThat(compressed.toBlockY(400.0)).isEqualTo(163);
    }

    @ParameterizedTest(name = "elevation {0} stays inside build limits")
    @ValueSource(doubles = {-11_000.0, -1000.0, 0.0, 1000.0, 8849.0, 100_000.0})
    void alwaysWithinBuildLimits(double elevation) {
        VerticalScale scale = defaultScale();
        int y = scale.toBlockY(elevation);
        assertThat(y).isBetween(scale.minY(), scale.maxY());
    }

    @Test
    @DisplayName("soft clamp keeps higher mountains higher")
    void softClampPreservesOrdering() {
        VerticalScale scale = defaultScale();
        int alps = scale.toBlockY(4808.0);   // Mont Blanc
        int everest = scale.toBlockY(8849.0);
        assertThat(everest).isGreaterThanOrEqualTo(alps);
        assertThat(everest).isLessThan(scale.maxY());
    }

    @Test
    void roundTripsWithinTheLinearRange() {
        VerticalScale scale = defaultScale();
        double elevation = 250.0;
        assertThat(scale.toElevationMeters(scale.rawY(elevation))).isCloseTo(elevation, Offset.offset(1e-9));
    }

    /** The frame a 1.16 client can see whole: y 0..255, sea at 63. */
    private static VerticalScale legacyFrameWithCurve() {
        return new VerticalScale(63, 0, 256, 1.0, 15.0, 1300.0);
    }

    @Test
    @DisplayName("the relief curve keeps metres-per-block at sea level and fits Everest in a 256-block world")
    void reliefCurveFitsTheEarthIntoTheLegacyFrame() {
        VerticalScale scale = legacyFrameWithCurve();

        assertThat(scale.rawY(0.0)).isEqualTo(63.0);
        // Near the sea a block is still ~15 m: a 100 m hill is ~6 blocks, not flattened away.
        assertThat(scale.rawY(100.0) - 63.0).isCloseTo(100.0 / 15.0, Offset.offset(0.3));
        // Everest keeps its true shape below the soft-clamp knee instead of being squashed flat.
        assertThat(scale.highestUncompressedElevation()).isGreaterThan(8849.0);
        assertThat(scale.toBlockY(8849.0)).isBetween(230, 247);
        // Below the sea the curve is symmetric: a 200 m continental shelf keeps its gentle slope.
        assertThat(scale.rawY(-200.0)).isCloseTo(63.0 - 200.0 / 15.0, Offset.offset(1.5));
        assertThat(scale.toBlockY(-4000.0)).isGreaterThan(scale.minY());
    }

    /**
     * Regression for mountains generating as single-column spikes: with a linear 20 m per block at a
     * kilometre per block, a range's slopes were multiplied fifty-fold. The curve must give a block at
     * mountain altitude several times the metres it gives at sea level.
     */
    @Test
    void mountainsGetGentlerSlopesThanLowlands() {
        VerticalScale scale = legacyFrameWithCurve();

        double lowland = scale.rawY(100.0) - scale.rawY(0.0);
        double mountain = scale.rawY(3100.0) - scale.rawY(3000.0);

        assertThat(mountain).isLessThan(lowland / 3.0);
        assertThat(scale.rawY(3100.0)).isGreaterThan(scale.rawY(3000.0)); // still strictly rising
    }

    @ParameterizedTest
    @ValueSource(doubles = {-9000.0, -250.0, -1.0, 0.0, 1.0, 250.0, 3000.0, 8849.0})
    void reliefCurveRoundTrips(double elevation) {
        VerticalScale scale = legacyFrameWithCurve();
        assertThat(scale.toElevationMeters(scale.rawY(elevation))).isCloseTo(elevation, Offset.offset(1e-6));
    }

    @Test
    void zeroReliefCurveIsTheLinearScale() {
        VerticalScale curved = new VerticalScale(63, -64, 320, 1.0, 1.0, 0.0);
        VerticalScale linear = defaultScale();
        for (double elevation : new double[] {-500, 0, 123.4, 4000}) {
            assertThat(curved.rawY(elevation)).isEqualTo(linear.rawY(elevation));
        }
        assertThatThrownBy(() -> new VerticalScale(63, -64, 320, 1.0, 1.0, -5.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new VerticalScale(63, 320, -64, 1.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerticalScale(-100, -64, 320, 1.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerticalScale(63, -64, 320, 0.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerticalScale(63, -64, 320, 1.0, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
