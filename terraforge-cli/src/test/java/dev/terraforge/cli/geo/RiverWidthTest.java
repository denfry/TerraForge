package dev.terraforge.cli.geo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RiverWidthTest {

    @Test
    void derivesWidthFromDischarge() {
        assertThat(RiverWidth.metres(100.0, 1.0)).isEqualTo(1_000.0);
        assertThat(RiverWidth.metres(100_000.0, 1.0)).isEqualTo(7.2 * Math.sqrt(100_000.0));
    }

    @Test
    void widensSubBlockRiversToOneBlock() {
        assertThat(RiverWidth.metres(1.0, 1.0)).isEqualTo(1_000.0);
        assertThat(RiverWidth.metres(1.0, 2.0)).isEqualTo(500.0);
    }
}
