package dev.terraforge.generator.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.core.terrain.TerrainSample;
import org.junit.jupiter.api.Test;

/**
 * Regression for the cliff-edged lakes: a lake at its own altitude in a plateau of DEM ground was a
 * slab of water at the bottom of a sheer-walled pit. Banks now slope in, beds now shelve out.
 */
class ShoreShaperTest {

    private static final int WIDTH = 24;
    private static final int WATER_Y = 100;

    @Test
    void banksClimbAtMostOneBlockPerBlockAwayFromTheWater() {
        TerrainSample[] grid = plateau(112); // twelve blocks above the lake
        lake(grid, 8, 16, 8, 16, WATER_Y - 6);

        ShoreShaper.shape(grid, WIDTH, 4, 0);

        assertThat(at(grid, 7, 12).surfaceY()).isEqualTo(WATER_Y + 1);
        assertThat(at(grid, 6, 12).surfaceY()).isEqualTo(WATER_Y + 2);
        assertThat(at(grid, 5, 12).surfaceY()).isEqualTo(WATER_Y + 3);
        assertThat(at(grid, 4, 12).surfaceY()).isEqualTo(WATER_Y + 4);
        assertThat(at(grid, 3, 12).surfaceY()).isEqualTo(112); // beyond reach: untouched
        assertThat(at(grid, 0, 0).surfaceY()).isEqualTo(112);
    }

    @Test
    void bedsShelveOutOneBlockPerBlockFromTheShore() {
        TerrainSample[] grid = plateau(101);
        lake(grid, 4, 20, 4, 20, WATER_Y - 6);

        ShoreShaper.shape(grid, WIDTH, 4, 0);

        assertThat(at(grid, 4, 12).surfaceY()).isEqualTo(WATER_Y - 1); // one block deep at the edge
        assertThat(at(grid, 5, 12).surfaceY()).isEqualTo(WATER_Y - 2);
        assertThat(at(grid, 7, 12).surfaceY()).isEqualTo(WATER_Y - 4);
        assertThat(at(grid, 12, 12).surfaceY()).isEqualTo(WATER_Y - 6); // the middle keeps its depth
        assertThat(at(grid, 12, 12).isWater()).isTrue();
    }

    @Test
    void lowBanksAndShallowBedsAreLeftAlone() {
        TerrainSample[] grid = plateau(WATER_Y + 1);
        lake(grid, 8, 16, 8, 16, WATER_Y - 1);
        TerrainSample[] before = grid.clone();

        ShoreShaper.shape(grid, WIDTH, 4, 0);

        assertThat(grid).containsExactly(before);
    }

    @Test
    void theMarginInformsButIsNotWritten() {
        TerrainSample[] grid = plateau(120);
        lake(grid, 0, 4, 0, WIDTH, WATER_Y - 6); // water entirely inside the margin

        ShoreShaper.shape(grid, WIDTH, 4, 4);

        assertThat(at(grid, 2, 12).surfaceY()).isEqualTo(WATER_Y - 6); // margin: untouched
        assertThat(at(grid, 4, 12).surfaceY()).isEqualTo(WATER_Y + 1); // interior bank: shaped
        assertThat(at(grid, 7, 12).surfaceY()).isEqualTo(WATER_Y + 4);
        assertThat(at(grid, 8, 12).surfaceY()).isEqualTo(120);
    }

    @Test
    void zeroReachDoesNothing() {
        TerrainSample[] grid = plateau(140);
        lake(grid, 8, 16, 8, 16, WATER_Y - 6);
        TerrainSample[] before = grid.clone();
        ShoreShaper.shape(grid, WIDTH, 0, 0);
        assertThat(grid).containsExactly(before);
    }

    private static TerrainSample[] plateau(int surfaceY) {
        TerrainSample[] grid = new TerrainSample[WIDTH * WIDTH];
        for (int i = 0; i < grid.length; i++) {
            grid[i] = new TerrainSample(surfaceY * 20.0, surfaceY, WaterType.NONE, 0,
                    LandcoverClass.GRASSLAND, ClimateBiome.GRASSLAND, false);
        }
        return grid;
    }

    private static void lake(TerrainSample[] grid, int x0, int x1, int z0, int z1, int bedY) {
        for (int z = z0; z < z1; z++) {
            for (int x = x0; x < x1; x++) {
                grid[z * WIDTH + x] = new TerrainSample(bedY * 20.0, bedY, WaterType.LAKE, WATER_Y,
                        LandcoverClass.PERMANENT_WATER, ClimateBiome.LAKE, false);
            }
        }
    }

    private static TerrainSample at(TerrainSample[] grid, int x, int z) {
        return grid[z * WIDTH + x];
    }
}
