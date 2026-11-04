package dev.terraforge.generator.vegetation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.terraforge.core.config.TerraForgeConfig.CocoaSection;
import dev.terraforge.core.config.TerraForgeConfig.DarkOakSection;
import dev.terraforge.core.config.TerraForgeConfig.KelpSection;
import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.core.terrain.TerrainSample;
import java.util.Random;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.generator.LimitedRegion;
import org.junit.jupiter.api.Test;

class ChunkFloraTest {

    private static final DarkOakSection NO_OAK = new DarkOakSection(0.0, 0.0);
    private static final CocoaSection NO_COCOA = new CocoaSection(0.0, 0.0);
    private static final KelpSection NO_KELP = new KelpSection(0.0, 12, 3, 1, 8, 1.0, 0);

    @Test
    void nothingConfiguredMeansNoPass() {
        assertThat(new ChunkFlora(NO_OAK, NO_COCOA, NO_KELP).enabled()).isFalse();
        assertThat(new ChunkFlora(new DarkOakSection(8.0, 0.0), NO_COCOA, NO_KELP).enabled()).isTrue();
        assertThat(new ChunkFlora(NO_OAK, new CocoaSection(0.6, 0.3), NO_KELP).enabled()).isTrue();
        // A chance of zero on either side hangs nothing.
        assertThat(new ChunkFlora(NO_OAK, new CocoaSection(0.6, 0.0), NO_KELP).enabled()).isFalse();
    }

    @Test
    void densityMapsOntoClimateBiomes() {
        ChunkFlora flora = new ChunkFlora(new DarkOakSection(8.0, 1.5), NO_COCOA, NO_KELP);

        assertThat(flora.darkOakDensity(ClimateBiome.TEMPERATE_RAINFOREST)).isEqualTo(8.0);
        assertThat(flora.darkOakDensity(ClimateBiome.TEMPERATE_FOREST)).isEqualTo(1.5);
        assertThat(flora.darkOakDensity(ClimateBiome.BOREAL_FOREST)).isZero();
    }

    @Test
    void countTakesTheFractionAsAChance() {
        assertThat(ChunkFlora.count(1.5, 0.4)).isEqualTo(2);
        assertThat(ChunkFlora.count(1.5, 0.6)).isEqualTo(1);
        assertThat(ChunkFlora.count(8.0, 0.0)).isEqualTo(8);
    }

    @Test
    void darkOaksRefuseGroundThatIsNotSoil() {
        ChunkFlora flora = new ChunkFlora(new DarkOakSection(8.0, 0.0), NO_COCOA, NO_KELP);

        LimitedRegion sand = region(Material.SAND);
        flora.populate(sand, land(ClimateBiome.TEMPERATE_RAINFOREST), 0, 0, 0, 255, 1L);
        verify(sand, never()).generateTree(any(Location.class), any(Random.class), any(TreeType.class));
    }

    @Test
    void darkOaksStayOutOfOtherForests() {
        LimitedRegion region = region(Material.GRASS_BLOCK);

        new ChunkFlora(new DarkOakSection(8.0, 0.0), NO_COCOA, NO_KELP)
                .populate(region, land(ClimateBiome.BOREAL_FOREST), 0, 0, 0, 255, 1L);

        verify(region, never()).generateTree(any(Location.class), any(Random.class), any(TreeType.class));
    }

    @Test
    void kelpGrowsOnlyInTemperateSeas() {
        assertThat(ChunkFlora.growsKelp(ClimateBiome.OCEAN)).isTrue();
        assertThat(ChunkFlora.growsKelp(ClimateBiome.DEEP_OCEAN)).isTrue();
        assertThat(ChunkFlora.growsKelp(ClimateBiome.WARM_OCEAN)).isFalse();
        assertThat(ChunkFlora.growsKelp(ClimateBiome.FROZEN_OCEAN)).isFalse();
    }

    @Test
    void kelpTopFollowsTheLimitAndStaysUnderWater() {
        KelpLimit server = new KelpLimit(0.5, 25);

        assertThat(ChunkFlora.kelpTop(10, 60, 1, KelpLimit.NONE)).isEqualTo(59);
        assertThat(ChunkFlora.kelpTop(10, 60, 1, server)).isEqualTo(35); // 49 -> 25, capped from 25
        assertThat(ChunkFlora.kelpTop(10, 30, 2, server)).isEqualTo(19); // 18 -> 9
        assertThat(ChunkFlora.kelpTop(10, 12, 8, server)).isEqualTo(11); // a shallow bed still gets a block
    }

    private static LimitedRegion region(Material ground) {
        LimitedRegion region = mock(LimitedRegion.class);
        when(region.generateTree(any(Location.class), any(Random.class), any(TreeType.class))).thenReturn(true);
        when(region.getType(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int y = call.getArgument(1);
            return y <= 70 ? ground : Material.AIR;
        });
        return region;
    }

    private static ChunkFlora.Samples land(ClimateBiome biome) {
        TerrainSample sample = new TerrainSample(100.0, 70, WaterType.NONE, 0, LandcoverClass.TREE_COVER, biome, false);
        return (x, z) -> sample;
    }
}
