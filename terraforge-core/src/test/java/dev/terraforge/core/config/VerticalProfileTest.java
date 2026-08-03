package dev.terraforge.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VerticalProfileTest {
    @Test
    void buildsProfileFromTerrainConfiguration() {
        assertThat(VerticalProfile.from(TerraForgeConfig.defaults().terrain()))
                .isEqualTo(new VerticalProfile(63, -64, 320, 1.0));
    }

    @Test
    void fingerprintIsStableForIdenticalValues() {
        var a = new VerticalProfile(63, -64, 320, 1.0);
        var b = new VerticalProfile(63, -64, 320, 1.0);
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
        assertThat(a.fingerprint()).hasSize(64);
    }

    @Test
    void fingerprintChangesWhenSeaLevelChanges() {
        var a = new VerticalProfile(63, -64, 320, 1.0);
        var b = new VerticalProfile(64, -64, 320, 1.0);
        assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
    }

    @Test
    void fingerprintChangesWhenMinYChanges() {
        var a = new VerticalProfile(0, -64, 320, 1.0);
        var b = new VerticalProfile(0, -80, 320, 1.0);
        assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
    }

    @Test
    void fingerprintChangesWhenMaxYChanges() {
        var a = new VerticalProfile(0, -64, 320, 1.0);
        var b = new VerticalProfile(0, -64, 336, 1.0);
        assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
    }

    @Test
    void fingerprintChangesWhenMetersPerBlockChanges() {
        var a = new VerticalProfile(63, -64, 320, 1.0);
        var b = new VerticalProfile(63, -64, 320, 2.0);
        assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
    }
}
