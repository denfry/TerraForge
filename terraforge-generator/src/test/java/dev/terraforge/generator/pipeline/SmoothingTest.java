package dev.terraforge.generator.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.projection.EquirectangularProjection;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.core.terrain.VerticalScale;
import org.junit.jupiter.api.Test;

/**
 * {@code terrain.smoothing}: the box filter that turned the audited world's one-block noise into
 * walkable ground. Measured as the mean absolute height step between neighbouring columns.
 */
class SmoothingTest {

    /** The audited planet frame: one block per kilometre, 20 m per block. */
    private static final VerticalScale PLANET = new VerticalScale(0, -512, 512, 1.0, 20.0);
    private static final CoordinateTransformer TRANSFORMER = new CoordinateTransformer(
            EquirectangularProjection.plateCarree(), new GeoPoint(51.0, 10.0), 1.0);
    /** The longitude one block spans in that frame, read from the transformer, not assumed. */
    private static final double BLOCK_DEGREES = TRANSFORMER.chunkBounds(0, 0).longitudeSpan() / 16;

    @Test
    void aWiderFootprintRemovesBlockToBlockNoise() {
        ElevationProvider rough = new NoisyElevation(BLOCK_DEGREES);

        double unsmoothed = meanStep(rough, 1.0);
        double smoothed = meanStep(rough, 3.0);

        // A two-block period is the worst case for a three-block box: it still halves the step.
        assertThat(unsmoothed).isGreaterThan(1.0);
        assertThat(smoothed).isLessThanOrEqualTo(unsmoothed / 2.0);
    }

    @Test
    void smoothingKeepsTheMeanHeightAndTheSeamBetweenChunks() {
        ElevationProvider rough = new NoisyElevation(BLOCK_DEGREES);
        ChunkSampler.ChunkSamples left = sampler(rough, 3.0).sample(0, 0);
        ChunkSampler.ChunkSamples right = sampler(rough, 3.0).sample(1, 0);

        // The filter is centred on each column, so it neither lifts nor sinks the terrain.
        double mean = 0.0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                mean += left.at(x, z).elevationMeters();
            }
        }
        assertThat(mean / 256).isCloseTo(NoisyElevation.BASE_METRES, org.assertj.core.data.Offset.offset(40.0));

        // And the last column of one chunk meets the first of the next as smoothly as any other pair.
        for (int z = 0; z < 16; z++) {
            int step = Math.abs(left.at(15, z).surfaceY() - right.at(0, z).surfaceY());
            assertThat(step).isLessThanOrEqualTo(2);
        }
    }

    @Test
    void smoothingBelowOneIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sampler(new NoisyElevation(BLOCK_DEGREES), 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static double meanStep(ElevationProvider elevation, double smoothing) {
        ChunkSampler.ChunkSamples samples = sampler(elevation, smoothing).sample(0, 0);
        double total = 0.0;
        int pairs = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 1; x < 16; x++) {
                TerrainSample a = samples.at(x - 1, z);
                TerrainSample b = samples.at(x, z);
                total += Math.abs(a.surfaceY() - b.surfaceY());
                pairs++;
            }
        }
        return total / pairs;
    }

    private static CachingChunkSampler sampler(ElevationProvider elevation, double smoothing) {
        var transformer = TRANSFORMER;
        var holder = new java.util.concurrent.atomic.AtomicReference<CachingChunkSampler>();
        DefaultTerrainPipeline.builder()
                .elevation(elevation)
                .verticalScale(PLANET)
                .fallbackElevation(0.0)
                .waterFeatures(false, false, false)
                .chunkSampler(pipeline -> {
                    var sampler = new CachingChunkSampler(pipeline, transformer, new CacheManager(64), 64, smoothing);
                    holder.set(sampler);
                    return sampler;
                })
                .build();
        return holder.get();
    }

    /**
     * Elevation whose footprint mean carries a deterministic, high-frequency wobble of about
     * +-60 m from block to block -- three blocks at 20 m per block, the kind of texture the
     * audited European plains showed. The wobble has a period of two blocks, so a 3x3 average
     * cancels most of it while a 1x1 footprint reads it in full.
     */
    private static final class NoisyElevation implements ElevationProvider {
        static final double BASE_METRES = 400.0;
        private final double blockDegrees;

        NoisyElevation(double blockDegrees) {
            this.blockDegrees = blockDegrees;
        }

        @Override
        public double elevationAt(double latitude, double longitude) {
            return BASE_METRES;
        }

        @Override
        public double averageElevationAt(double latitude, double longitude,
                                         double latitudeSpanDegrees, double longitudeSpanDegrees) {
            // Integrate a two-block-period square wave over the box.
            double min = longitude - longitudeSpanDegrees / 2.0;
            double max = longitude + longitudeSpanDegrees / 2.0;
            int steps = 64;
            double sum = 0.0;
            for (int i = 0; i < steps; i++) {
                double lon = min + (max - min) * (i + 0.5) / steps;
                long cell = (long) Math.floor(lon / blockDegrees);
                sum += BASE_METRES + ((cell & 1) == 0 ? 60.0 : -60.0);
            }
            return sum / steps;
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
