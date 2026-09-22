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

    /**
     * Regression for the clouds vanishing from every overworld-type world: the pack's dimension type
     * had no {@code cloud_height}, and vanilla 1.21.8's overworld has {@code "cloud_height": 192}.
     */
    @Test
    void keepsVanillasCloudHeight() {
        String overworld = new String(TerraForgeDatapack.render(VerticalProfile.regional()).files()
                .get("data/minecraft/dimension_type/overworld.json"));
        assertThat(overworld).contains("\"cloud_height\": 192");
        // Everything vanilla 1.21.8's overworld.json declares, so the pack changes the height only.
        assertThat(overworld).contains("\"ambient_light\": 0.0", "\"bed_works\": true",
                "\"coordinate_scale\": 1.0", "\"effects\": \"minecraft:overworld\"", "\"has_ceiling\": false",
                "\"has_raids\": true", "\"has_skylight\": true", "\"height\": 384",
                "\"infiniburn\": \"#minecraft:infiniburn_overworld\"", "\"logical_height\": 384",
                "\"min_y\": -64", "\"monster_spawn_block_light_limit\": 0", "\"natural\": true",
                "\"piglin_safe\": false", "\"respawn_anchor_works\": false", "\"ultrawarm\": false");
    }

    @Test
    void fingerprintsProfilesAndRejectsMissingProfile() {
        assertThat(TerraForgeDatapack.render(new VerticalProfile(0, -512, 512, 20.0)).fingerprint())
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(TerraForgeDatapack.render(VerticalProfile.regional()).fingerprint());
        assertThatThrownBy(() -> TerraForgeDatapack.render(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
