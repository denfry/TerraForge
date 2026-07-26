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
