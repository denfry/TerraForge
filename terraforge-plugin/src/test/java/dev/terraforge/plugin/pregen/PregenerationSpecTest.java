package dev.terraforge.plugin.pregen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PregenerationSpecTest {
    @Test
    void convertsAnInclusiveBlockRadiusToChunkBounds() {
        var spec = PregenerationSpec.around(0, 0, 16);

        assertThat(spec.minChunkX()).isEqualTo(-1);
        assertThat(spec.maxChunkX()).isEqualTo(1);
        assertThat(spec.minChunkZ()).isEqualTo(-1);
        assertThat(spec.maxChunkZ()).isEqualTo(1);
        assertThat(spec.totalChunks()).isEqualTo(9);
    }

    @Test
    void zeroRadiusContainsOnlyTheCenterChunk() {
        var spec = PregenerationSpec.around(31, -17, 0);

        assertThat(spec.minChunkX()).isEqualTo(1);
        assertThat(spec.maxChunkX()).isEqualTo(1);
        assertThat(spec.minChunkZ()).isEqualTo(-2);
        assertThat(spec.maxChunkZ()).isEqualTo(-2);
        assertThat(spec.totalChunks()).isOne();
    }

    @Test
    void rejectsNegativeRadiusAndOverflowingBounds() {
        assertThatThrownBy(() -> PregenerationSpec.around(0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PregenerationSpec.around(Integer.MAX_VALUE, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
