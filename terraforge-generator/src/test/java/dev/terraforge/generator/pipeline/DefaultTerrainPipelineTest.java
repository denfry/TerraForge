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
import dev.terraforge.core.data.WaterProvider;
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
    void aDeepOceanIsStillOceanEvenThoughItsFloorIsKilometresFromItsSurface() {
        // The ocean's surface is sea level by definition, so it is never cross-checked against the
        // sea floor. Applying the lake's plausibility test here would turn every abyssal column into
        // dry land.
        DefaultTerrainPipeline pipeline = withWater(bathymetry(-4000.0),
                water(WaterType.OCEAN, 0.0, 0.0));

        TerrainSample sample = pipeline.sampleColumn(0.0, -30.0);

        assertThat(sample.waterType()).isEqualTo(WaterType.OCEAN);
        assertThat(sample.elevationMeters()).isEqualTo(-4000.0);
        assertThat(sample.waterSurfaceY()).isEqualTo(SCALE.seaLevel());
        assertThat(sample.isUnderwater()).isTrue();
    }

    @Test
    void aSeaTooShallowToRepresentIsStillWetRatherThanADryBasin() {
        // Half a metre of real bathymetry rounds to the same block as the surface. Writing the bed
        // there leaves a dry seabed where the map says ocean; the bed drops one block instead.
        DefaultTerrainPipeline pipeline = withWater(bathymetry(-0.5), water(WaterType.OCEAN, 0.0, 0.0));

        TerrainSample sample = pipeline.sampleColumn(0.0, -30.0);

        assertThat(sample.waterType()).isEqualTo(WaterType.OCEAN);
        assertThat(sample.surfaceY()).isEqualTo(SCALE.seaLevel() - 1);
        assertThat(sample.isUnderwater()).isTrue();
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

    @Test
    void riverWaterSitsAboveItsWidthDerivedBed() {
        DefaultTerrainPipeline pipeline = withWater(constant(100.0),
                water(WaterType.RIVER, ElevationProvider.NO_DATA, 4.0));

        TerrainSample sample = pipeline.sampleColumn(50.0, 8.0);

        assertThat(sample.waterType()).isEqualTo(WaterType.RIVER);
        assertThat(sample.waterSurfaceY()).isEqualTo(163);
        assertThat(sample.surfaceY()).isLessThan(sample.waterSurfaceY());
    }

    @Test
    void aMountainLakeSitsAtItsOwnAltitudeAndHoldsWater() {
        // Lake Geneva: the DEM reads the water surface, HydroLAKES states it, and the bed is carved
        // below it -- so the lake is 372 m up, not at sea level, and it is not dry either.
        DefaultTerrainPipeline pipeline = withWater(constant(372.0),
                water(WaterType.LAKE, 372.0, 40.0));

        TerrainSample sample = pipeline.sampleColumn(46.45, 6.5);

        assertThat(sample.waterType()).isEqualTo(WaterType.LAKE);
        assertThat(sample.waterSurfaceY()).isEqualTo(SCALE.toBlockY(372.0));
        assertThat(sample.surfaceY()).isEqualTo(SCALE.toBlockY(332.0));
        assertThat(sample.isUnderwater()).isTrue();
    }

    @Test
    void aLakeWithNoKnownSurfaceStaysDryLandInsteadOfCollapsingToSeaLevel() {
        // The catastrophe this guards: a 5,440 m Tibetan column told "the lake surface is unknown"
        // must keep its mountain. Substituting sea level removed 277 blocks of terrain and left a
        // one-block pond at y=0 behind.
        DefaultTerrainPipeline pipeline = withWater(constant(5440.0),
                water(WaterType.LAKE, ElevationProvider.NO_DATA, 0.0));

        TerrainSample sample = pipeline.sampleColumn(30.49, 84.07);

        assertThat(sample.waterType()).isEqualTo(WaterType.NONE);
        assertThat(sample.elevationMeters()).isEqualTo(5440.0);
        assertThat(sample.surfaceY()).isEqualTo(SCALE.toBlockY(5440.0));
    }

    @Test
    void aLakeSurfaceKilometresFromTheTerrainIsRejectedAsBadData() {
        // Same column, but now the source insists the lake is at sea level. Believing it would carve
        // 5.4 km off the plateau; the DEM is the cross-check that says the attribute is wrong.
        DefaultTerrainPipeline pipeline = withWater(constant(5440.0), water(WaterType.LAKE, 0.0, 0.0));

        TerrainSample sample = pipeline.sampleColumn(30.49, 84.07);

        assertThat(sample.waterType()).isEqualTo(WaterType.NONE);
        assertThat(sample.surfaceY()).isEqualTo(SCALE.toBlockY(5440.0));
    }

    @Test
    void aBlocksElevationIsTheMeanOverItsFootprintNotItsCentre() {
        // A one-block-per-km world: the centre sample is a spike, the footprint is not. Sampling the
        // centre would report 1000 m of relief that the ground does not have.
        ElevationProvider spiky = new ElevationProvider() {
            @Override public double elevationAt(double latitude, double longitude) { return 1000.0; }
            @Override public double averageElevationAt(double latitude, double longitude,
                                                       double latitudeSpanDegrees, double longitudeSpanDegrees) {
                return latitudeSpanDegrees > 0.0 ? 100.0 : elevationAt(latitude, longitude);
            }
            @Override public boolean hasCoverage(double latitude, double longitude) { return true; }
            @Override public GeoBounds coverage() { return GeoBounds.world(); }
            @Override public boolean hasBathymetry() { return false; }
        };
        DefaultTerrainPipeline pipeline = DefaultTerrainPipeline.builder().elevation(spiky)
                .verticalScale(SCALE).chunkSampler(DefaultTerrainPipelineTest::sampler).build();

        assertThat(pipeline.sampleColumn(50.0, 8.0).elevationMeters()).isEqualTo(1000.0);
        assertThat(pipeline.sampleChunk(0, 0).at(8, 8).elevationMeters()).isEqualTo(100.0);
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

    /** A DEM with real depths merged in, so the water stage trusts the sampled sea floor. */
    private static ElevationProvider bathymetry(double metres) {
        return new ElevationProvider() {
            @Override public double elevationAt(double latitude, double longitude) { return metres; }
            @Override public boolean hasCoverage(double latitude, double longitude) { return true; }
            @Override public GeoBounds coverage() { return GeoBounds.world(); }
            @Override public boolean hasBathymetry() { return true; }
        };
    }

    /** A pipeline over a fixed elevation and a fixed water answer. */
    private static DefaultTerrainPipeline withWater(ElevationProvider elevation, WaterProvider water) {
        return DefaultTerrainPipeline.builder().elevation(elevation).water(water)
                .verticalScale(SCALE).chunkSampler(DefaultTerrainPipelineTest::sampler).build();
    }

    /** One water answer everywhere, as a prepared vector source would report it. */
    private static WaterProvider water(WaterType type, double surfaceMetres, double bedDepthMetres) {
        return new WaterProvider() {
            @Override
            public WaterType waterTypeAt(double latitude, double longitude) {
                return type;
            }

            @Override
            public WaterColumn waterColumnAt(double latitude, double longitude, double knownElevationMeters) {
                // A river takes the terrain height, exactly as the prepared providers do.
                double surface = type == WaterType.RIVER && ElevationProvider.isNoData(surfaceMetres)
                        ? knownElevationMeters : surfaceMetres;
                return new WaterColumn(type, surface, bedDepthMetres);
            }
        };
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
