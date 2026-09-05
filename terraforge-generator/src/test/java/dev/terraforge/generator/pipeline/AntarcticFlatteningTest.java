package dev.terraforge.generator.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.projection.EquirectangularProjection;
import dev.terraforge.core.terrain.Antarctica;
import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.core.terrain.VerticalScale;
import org.junit.jupiter.api.Test;

/**
 * Regression for the Antarctic plateau: at 20 m per block a 3 km ice dome read straight from the
 * DEM was a 150-block cliff of packed ice. South of 60 degrees the pipeline draws the sheet as a
 * low dome instead, and the whole of it is ice sheet.
 */
class AntarcticFlatteningTest {

    private static final VerticalScale PLANET = new VerticalScale(0, -512, 512, 1.0, 20.0);
    private static final CoordinateTransformer TRANSFORMER = new CoordinateTransformer(
            EquirectangularProjection.plateCarree(), new GeoPoint(51.0, 10.0), 1.0);

    @Test
    void theIceSheetIsFlattenedButTheRestOfTheWorldIsNot() {
        Plateau dem = new Plateau(3_000.0);
        DefaultTerrainPipeline pipeline = pipeline(dem);

        TerrainSample antarctic = pipeline.sampleColumn(-80.0, 90.0);
        TerrainSample alpine = pipeline.sampleColumn(46.5, 8.0);

        assertThat(antarctic.elevationMeters()).isEqualTo(Antarctica.flatten(dem.elevationAt(-80.0, 90.0)));
        assertThat(antarctic.surfaceY()).isLessThan(12); // a few blocks above the sea, not 150
        assertThat(antarctic.biome()).isEqualTo(ClimateBiome.GLACIER);
        assertThat(alpine.elevationMeters()).isEqualTo(3_000.0);
        assertThat(alpine.surfaceY()).isEqualTo(150);
    }

    @Test
    void theSheetIsFlatFromColumnToColumn() {
        DefaultTerrainPipeline pipeline = pipeline(new Plateau(2_500.0));
        int previous = pipeline.sampleColumn(-70.0, 0.0).surfaceY();
        for (double latitude = -70.1; latitude > -85.0; latitude -= 0.1) {
            int y = pipeline.sampleColumn(latitude, 0.0).surfaceY();
            assertThat(Math.abs(y - previous)).isLessThanOrEqualTo(1);
            previous = y;
        }
    }

    private static DefaultTerrainPipeline pipeline(ElevationProvider elevation) {
        return DefaultTerrainPipeline.builder()
                .elevation(elevation)
                .verticalScale(PLANET)
                .fallbackElevation(0.0)
                .waterFeatures(false, false, false)
                .chunkSampler(p -> new CachingChunkSampler(p, TRANSFORMER, new CacheManager(16), 16, 1.0))
                .build();
    }

    /** A DEM that rises linearly towards the pole from a base height, like the real ice dome. */
    private record Plateau(double base) implements ElevationProvider {
        @Override
        public double elevationAt(double latitude, double longitude) {
            return latitude < -60.0 ? base + (-60.0 - latitude) * 50.0 : base;
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
