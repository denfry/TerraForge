package dev.terraforge.core.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.terraforge.core.config.VerticalProfile;
import org.junit.jupiter.api.Test;

class TerraForgeDatapackTest {
    @Test
    void rendersThePaper1218HeightPackDeterministically() {
        var rendered = TerraForgeDatapack.render(new VerticalProfile(0, -512, 512, 20.0));
        assertThat(rendered.files()).containsKeys("pack.mcmeta", "data/minecraft/dimension_type/overworld.json");
        assertThat(new String(rendered.files().get("pack.mcmeta"))).contains("\"pack_format\": 81");
        assertThat(new String(rendered.files().get("data/minecraft/dimension_type/overworld.json")))
                .contains("\"min_y\": -512", "\"height\": 1024", "\"logical_height\": 1024");
        assertThat(TerraForgeDatapack.render(new VerticalProfile(0, -512, 512, 20.0)).fingerprint())
                .isEqualTo(rendered.fingerprint());
    }

    @Test
    void fingerprintsProfilesAndRejectsMissingProfile() {
        assertThat(TerraForgeDatapack.render(new VerticalProfile(0, -512, 512, 20.0)).fingerprint())
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(TerraForgeDatapack.render(VerticalProfile.regional()).fingerprint());
        assertThatThrownBy(() -> TerraForgeDatapack.render(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
