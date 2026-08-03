package dev.terraforge.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VerticalProfileTest {
    @Test
    void buildsProfileFromTerrainConfiguration() {
        assertThat(VerticalProfile.from(TerraForgeConfig.defaults().terrain()))
                .isEqualTo(new VerticalProfile(63, -64, 320, 1.0));
    }
}
