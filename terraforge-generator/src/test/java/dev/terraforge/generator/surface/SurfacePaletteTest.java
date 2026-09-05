package dev.terraforge.generator.surface;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.terraforge.core.terrain.ClimateBiome;
import java.util.EnumMap;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** The two mixed surfaces: the sea bed and the polar ice sheet. */
class SurfacePaletteTest {

    private static final int SIDE = 400;

    @Test
    void theSeaBedIsGravelSandClayBoneAndObsidianInTheStatedShares() {
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                SurfacePalette.Layers layers = SurfacePalette.forColumn(ClimateBiome.OCEAN, x - 200, z - 200);
                assertThat(layers.filler()).isEqualTo(layers.top());
                counts.merge(layers.top(), 1, Integer::sum);
            }
        }
        double total = SIDE * SIDE;
        assertThat(counts.keySet()).containsExactlyInAnyOrder(Material.GRAVEL, Material.SAND, Material.CLAY,
                Material.BONE_BLOCK, Material.OBSIDIAN);
        assertThat(counts.get(Material.GRAVEL) / total).isCloseTo(0.25, within(0.03));
        assertThat(counts.get(Material.SAND) / total).isCloseTo(0.35, within(0.03));
        assertThat(counts.get(Material.CLAY) / total).isCloseTo(0.15, within(0.03));
        assertThat(counts.get(Material.BONE_BLOCK) / total).isCloseTo(0.20, within(0.03));
        assertThat(counts.get(Material.OBSIDIAN) / total).isCloseTo(0.05, within(0.02));
    }

    @Test
    void theSeaBedComesInPatchesNotStatic() {
        // Most columns share their block with the column beside them: patches, not per-block noise.
        int same = 0;
        for (int x = 0; x < SIDE; x++) {
            for (int z = 1; z < SIDE; z++) {
                if (SurfacePalette.seabed(x, z) == SurfacePalette.seabed(x, z - 1)) {
                    same++;
                }
            }
        }
        assertThat(same / (double) (SIDE * (SIDE - 1))).isGreaterThan(0.55);
    }

    @Test
    void everyOceanSharesTheBedAndLakesKeepSand() {
        for (ClimateBiome ocean : new ClimateBiome[] {ClimateBiome.OCEAN, ClimateBiome.DEEP_OCEAN,
            ClimateBiome.WARM_OCEAN, ClimateBiome.FROZEN_OCEAN}) {
            assertThat(SurfacePalette.forColumn(ocean, 17, -42))
                    .isEqualTo(SurfacePalette.forColumn(ClimateBiome.OCEAN, 17, -42));
        }
        assertThat(SurfacePalette.forColumn(ClimateBiome.LAKE, 17, -42).top()).isEqualTo(Material.SAND);
        assertThat(SurfacePalette.forColumn(ClimateBiome.RIVER, 17, -42).top()).isEqualTo(Material.SAND);
        assertThat(SurfacePalette.forColumn(ClimateBiome.GRASSLAND, 17, -42))
                .isEqualTo(SurfacePalette.forBiome(ClimateBiome.GRASSLAND));
    }

    @Test
    void theIceSheetIsSnowWithAtMostATenthOfIce() {
        int ice = 0;
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                SurfacePalette.Layers layers = SurfacePalette.forColumn(ClimateBiome.GLACIER, x, z);
                assertThat(layers.top()).isIn(Material.SNOW_BLOCK, Material.PACKED_ICE);
                assertThat(layers.filler()).isEqualTo(layers.top());
                if (layers.top() == Material.PACKED_ICE) {
                    ice++;
                }
            }
        }
        double share = ice / (double) (SIDE * SIDE);
        assertThat(share).isLessThanOrEqualTo(SurfacePalette.ICE_SHEET_ICE_SHARE + 0.02);
        assertThat(share).isGreaterThan(0.05);
    }

    @Test
    void theSameColumnAlwaysGetsTheSameBlock() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(SurfacePalette.seabed(i * 31, -i * 17)).isEqualTo(SurfacePalette.seabed(i * 31, -i * 17));
        }
    }
}
