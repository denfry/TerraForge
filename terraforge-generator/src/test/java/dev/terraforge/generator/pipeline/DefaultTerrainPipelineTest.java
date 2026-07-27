package dev.terraforge.generator.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.data.ConstantLandcoverProvider;
import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.core.projection.EquirectangularProjection;
import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.core.terrain.VerticalScale;
import java.util.function.DoubleBinaryOperator;
import org.junit.jupiter.api.Test;

class DefaultTerrainPipelineTest {

    private static final VerticalScale SCALE = new VerticalScale(63, -64, 320, 1.0, 1.0);

    @Test
    void elevationBecomesASurfaceHeightAboveSeaLevel() {
        DefaultTerrainPipeline pipeline = pipeline((lat, lon) -> 100.0);

        TerrainSample sample = pipeline.sampleColumn(50.0, 8.0);

        assertThat(sample.elevationMeters()).isEqualTo(100.0);
        assertThat(sample.surfaceY()).isEqualTo(163);
        assertThat(sample.isWater()).isFalse();
        assertThat(sample.fromFallback()).isFalse();
        assertThat(sample.biome()).isEqualTo(ClimateBiome.TEMPERATE_FOREST);
    }

    @Test
    void missingElevationFallsBackAndSaysSo() {
        DefaultTerrainPipeline pipeline = pipeline((lat, lon) -> ElevationProvider.NO_DATA);

        TerrainSample sample = pipeline.sampleColumn(50.0, 8.0);

        assertThat(sample.fromFallback()).isTrue();
        assertThat(sample.elevationMeters()).isEqualTo(0.0);
        assertThat(sample.surfaceY()).isEqualTo(SCALE.seaLevel());
        // A coverage gap must not flood: no data is not evidence of ocean.
        assertThat(sample.waterType()).isEqualTo(WaterType.NONE);
    }

    @Test
    void landBelowSeaLevelBecomesOceanAtTheConfiguredDepth() {
        DefaultTerrainPipeline pipeline = pipeline((lat, lon) -> -5.0);

        TerrainSample sample = pipeline.sampleColumn(50.0, 8.0);

        assertThat(sample.waterType()).isEqualTo(WaterType.OCEAN);
        // No bathymetry in the source, so the floor is the configured default depth, and the
        // sample is marked as substituted because that depth was never measured.
        assertThat(sample.elevationMeters()).isEqualTo(-30.0);
        assertThat(sample.surfaceY()).isEqualTo(33);
        assertThat(sample.waterSurfaceY()).isEqualTo(SCALE.seaLevel());
        assertThat(sample.fromFallback()).isTrue();
        assertThat(sample.isUnderwater()).isTrue();
    }

    @Test
    void disablingOceansLeavesTheSeaFloorDry() {
        DefaultTerrainPipeline pipeline = DefaultTerrainPipeline.builder()
                .elevation(constant(-5.0))
                .verticalScale(SCALE)
                .waterFeatures(false, false, false)
                .chunkSampler(p -> sampler(p))
                .build();

        TerrainSample sample = pipeline.sampleColumn(50.0, 8.0);

        assertThat(sample.waterType()).isEqualTo(WaterType.NONE);
        assertThat(sample.elevationMeters()).isEqualTo(-5.0);
    }

    @Test
    void samplingIsDeterministic() {
        DefaultTerrainPipeline pipeline = pipeline((lat, lon) -> lat * 10.0 + lon);

        for (int i = 0; i < 5; i++) {
            assertThat(pipeline.sampleColumn(47.3, 8.7))
                    .isEqualTo(pipeline.sampleColumn(47.3, 8.7));
        }
    }

    @Test
    void aChunkIsSampledOnceAndThenServedFromTheCache() {
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        DefaultTerrainPipeline pipeline = pipeline((lat, lon) -> {
            counter.incrementAndGet();
            return 100.0;
        });

        ChunkSampler.ChunkSamples first = pipeline.sampleChunk(0, 0);
        ChunkSampler.ChunkSamples second = pipeline.sampleChunk(0, 0);

        assertThat(second).isSameAs(first);
        assertThat(counter.get()).isEqualTo(256);
        assertThat(first.at(15, 15)).isNotNull();
        assertThat(first.usedFallback()).isFalse();
    }

    @Test
    void chunkSamplesCoverTheChunksGeographicBounds() {
        DefaultTerrainPipeline pipeline = pipeline((lat, lon) -> 100.0);

        ChunkSampler.ChunkSamples samples = pipeline.sampleChunk(3, -2);
        GeoBounds bounds = samples.bounds();

        assertThat(bounds.minLatitude()).isLessThan(bounds.maxLatitude());
        assertThat(samples.chunkX()).isEqualTo(3);
        assertThat(samples.chunkZ()).isEqualTo(-2);
    }

    @Test
    void invalidateDropsCachedChunks() {
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        DefaultTerrainPipeline pipeline = pipeline((lat, lon) -> {
            counter.incrementAndGet();
            return 100.0;
        });

        pipeline.sampleChunk(0, 0);
        pipeline.invalidate();
        pipeline.sampleChunk(0, 0);

        assertThat(counter.get()).isEqualTo(512);
    }

    @Test
    void landcoverReachesTheBiomeResolver() {
        DefaultTerrainPipeline pipeline = DefaultTerrainPipeline.builder()
                .elevation(constant(200.0))
                .landcover(new ConstantLandcoverProvider(LandcoverClass.BARE_SPARSE))
                .verticalScale(SCALE)
                .chunkSampler(DefaultTerrainPipelineTest::sampler)
                .build();

        assertThat(pipeline.sampleColumn(25.0, 10.0).biome()).isEqualTo(ClimateBiome.DESERT);
    }

    // --- fixtures -----------------------------------------------------------

    private static DefaultTerrainPipeline pipeline(DoubleBinaryOperator elevation) {
        return DefaultTerrainPipeline.builder()
                .elevation(new FakeElevation(elevation))
                .verticalScale(SCALE)
                .defaultOceanDepth(30.0)
                .chunkSampler(DefaultTerrainPipelineTest::sampler)
                .build();
    }

    private static ElevationProvider constant(double meters) {
        return new FakeElevation((lat, lon) -> meters);
    }

    private static CachingChunkSampler sampler(TerrainPipeline pipeline) {
        var transformer = new CoordinateTransformer(
                EquirectangularProjection.plateCarree(), new GeoPoint(51.0, 10.0), 1.0);
        return new CachingChunkSampler(pipeline, transformer, new CacheManager(64), 256);
    }

    /** Elevation from a function, so a test can shape terrain without touching the filesystem. */
    private record FakeElevation(DoubleBinaryOperator function) implements ElevationProvider {

        @Override
        public double elevationAt(double latitude, double longitude) {
            return function.applyAsDouble(latitude, longitude);
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
            return false;
        }
    }
}
