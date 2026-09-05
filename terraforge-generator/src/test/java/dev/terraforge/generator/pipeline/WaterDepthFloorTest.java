package dev.terraforge.generator.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.data.WaterProvider;
import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.core.terrain.VerticalScale;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Regression for the one-block lakes: at 20 m per block a 6 m lake and a 30 m default ocean both
 * rounded to a single block of water over a flat floor. Every water body now has a depth floor in
 * blocks, and its bed varies smoothly below that floor so it reads as a basin.
 */
class WaterDepthFloorTest {

    /** The audited planet frame: 20 m per block. */
    private static final VerticalScale PLANET = new VerticalScale(0, -512, 512, 1.0, 20.0);
    /** A block's footprint in that frame, roughly. */
    private static final double SPAN = 0.009;

    @Test
    void aShallowLakeIsAtLeastTheFloorDeepAndNeverDeeperThanTwiceIt() {
        DefaultTerrainPipeline pipeline = pipeline(WaterType.LAKE, 500.0, 6.0);
        Set<Integer> depths = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            TerrainSample sample = pipeline.sampleColumn(48.0 + i * SPAN, 8.0, SPAN, SPAN);
            int depth = sample.waterSurfaceY() - sample.surfaceY();
            assertThat(depth).isBetween(3, 6);
            depths.add(depth);
        }
        assertThat(depths.size()).as("a basin, not a slab").isGreaterThan(1);
    }

    @Test
    void theDefaultOceanIsAtLeastSixBlocksDeepAndRealBathymetryIsUntouched() {
        DefaultTerrainPipeline shallow = pipeline(WaterType.OCEAN, 0.0, 0.0);
        TerrainSample fallback = shallow.sampleColumn(48.0, 8.0, SPAN, SPAN);
        assertThat(fallback.waterSurfaceY() - fallback.surfaceY()).isBetween(6, 12);

        DefaultTerrainPipeline deep = DefaultTerrainPipeline.builder()
                .elevation(new Flat(-2_000.0, true))
                .verticalScale(PLANET)
                .fallbackElevation(0.0)
                .minimumDepthBlocks(3, 2, 6)
                .water(constantWater(WaterType.OCEAN, 0.0, 0.0))
                .chunkSampler(p -> null)
                .build();
        TerrainSample abyss = deep.sampleColumn(48.0, 8.0, SPAN, SPAN);
        assertThat(abyss.waterSurfaceY() - abyss.surfaceY()).isEqualTo(100);
    }

    @Test
    void withoutAFootprintTheFloorStillAppliesButWithoutVariation() {
        DefaultTerrainPipeline pipeline = pipeline(WaterType.RIVER, 500.0, 1.0);
        for (int i = 0; i < 50; i++) {
            TerrainSample sample = pipeline.sampleColumn(48.0 + i * SPAN, 8.0);
            assertThat(sample.waterSurfaceY() - sample.surfaceY()).isEqualTo(2);
        }
    }

    @Test
    void landIsNotTouched() {
        DefaultTerrainPipeline pipeline = pipeline(WaterType.NONE, 0.0, 0.0);
        TerrainSample land = pipeline.sampleColumn(48.0, 8.0, SPAN, SPAN);
        assertThat(land.surfaceY()).isEqualTo(25);
    }

    private static DefaultTerrainPipeline pipeline(WaterType type, double surface, double bedDepth) {
        return DefaultTerrainPipeline.builder()
                .elevation(new Flat(500.0, false))
                .verticalScale(PLANET)
                .fallbackElevation(0.0)
                .minimumDepthBlocks(3, 2, 6)
                .water(constantWater(type, surface, bedDepth))
                .chunkSampler(p -> null)
                .build();
    }

    private static WaterProvider constantWater(WaterType type, double surface, double bedDepth) {
        return new WaterProvider() {
            @Override
            public WaterType waterTypeAt(double latitude, double longitude) {
                return type;
            }

            @Override
            public WaterColumn waterColumnAt(double latitude, double longitude, double knownElevationMeters) {
                return new WaterColumn(type, surface, bedDepth);
            }
        };
    }

    private record Flat(double metres, boolean bathymetry) implements ElevationProvider {
        @Override
        public double elevationAt(double latitude, double longitude) {
            return metres;
        }

        @Override
        public boolean hasCoverage(double latitude, double longitude) {
            return true;
        }

        @Override
        public GeoBounds coverage() {
            return GeoBounds.world();
        }

        @Override
        public boolean hasBathymetry() {
            return bathymetry;
        }
    }
}
