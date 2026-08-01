package dev.terraforge.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RegionOptionsTest {

    @Test
    void wholeWorldIsThePlanet() {
        RegionOptions options = new RegionOptions();
        options.wholeWorld = true;

        var bounds = options.bounds();

        assertThat(bounds.minLatitude()).isEqualTo(-90.0);
        assertThat(bounds.maxLatitude()).isEqualTo(90.0);
        assertThat(bounds.minLongitude()).isEqualTo(-180.0);
        assertThat(bounds.maxLongitude()).isEqualTo(180.0);
    }

    /** Two ways of saying where the region is, given at once, is a typo worth naming. */
    @Test
    void refusesWholeWorldMixedWithExplicitEdges() {
        RegionOptions options = new RegionOptions();
        options.wholeWorld = true;
        options.latMin = 47.0;

        assertThatThrownBy(options::bounds)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--whole-world already sets every edge");
    }

    @Test
    void refusesAPartialBox() {
        RegionOptions options = new RegionOptions();
        options.latMin = 47.0;
        options.latMax = 55.5;
        options.lonMin = 5.0;

        assertThatThrownBy(options::bounds)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--whole-world for the entire planet");
    }

    @Test
    void acceptsAnOrdinaryBox() {
        RegionOptions options = new RegionOptions();
        options.latMin = 47.0;
        options.latMax = 55.5;
        options.lonMin = 5.0;
        options.lonMax = 15.5;

        assertThat(options.bounds().minLatitude()).isEqualTo(47.0);
    }

    @Test
    void refusesAnInvertedBox() {
        RegionOptions options = new RegionOptions();
        options.latMin = 55.5;
        options.latMax = 47.0;
        options.lonMin = 5.0;
        options.lonMax = 15.5;

        assertThatThrownBy(options::bounds)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lat-min < lat-max");
    }
}
