package dev.terraforge.generator.vegetation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KelpLimitTest {

    @Test
    void cutsToTheShareRoundedUpAndNeverToNothing() {
        KelpLimit half = new KelpLimit(0.5, 0);

        assertThat(half.apply(55)).isEqualTo(28);
        assertThat(half.apply(56)).isEqualTo(28);
        assertThat(half.apply(2)).isEqualTo(1);
        assertThat(half.apply(1)).isEqualTo(1);
    }

    @Test
    void maxHeightCapsTheCutLength() {
        assertThat(new KelpLimit(0.5, 25).apply(80)).isEqualTo(25);
        assertThat(new KelpLimit(1.0, 25).apply(10)).isEqualTo(10);
    }

    @Test
    void onlyALimitedStalkStopsGrowing() {
        assertThat(KelpLimit.NONE.stopsGrowth()).isFalse();
        assertThat(KelpLimit.NONE.apply(55)).isEqualTo(55);
        assertThat(new KelpLimit(0.5, 0).stopsGrowth()).isTrue();
        assertThat(new KelpLimit(1.0, 25).stopsGrowth()).isTrue();
    }
}
