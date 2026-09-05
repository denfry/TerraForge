package dev.terraforge.generator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.data.ConstantLandcoverProvider;
import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.data.WaterProvider;
import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.core.projection.EquirectangularProjection;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.core.terrain.VerticalScale;
import org.junit.jupiter.api.Test;

/**
 * The whole assembled stack against the terrain that a previous build destroyed.
 *
 * <p>Configured exactly like the audited world -- {@code min-y -512}, {@code sea-level 0},
 * {@code 20 m} per block, one block per kilometre -- because the defect was invisible at
 * one metre per block and catastrophic at twenty: every metre of error became a twentieth of a
 * block, so a lake surface assumed to be at sea level removed 277 blocks of Tibetan plateau.
 */
class TerrainStackWaterTest {

    /** The audited world's vertical frame: 1 block = 20 m, sea level at y=0. */
    private static final VerticalScale AUDITED_SCALE = new VerticalScale(0, -512, 512, 1.0, 20.0);

    /** 30.4933 N 84.0688 E: DEM 5,440 m, and the column the audit found flattened to y=-1. */
    private static final double TIBET_LATITUDE = 30.4933;
    private static final double TIBET_LONGITUDE = 84.0688;
    private static final double TIBET_METRES = 5440.5;

    @Test
    void aTibetanLakeWithAnUnknownSurfaceKeepsTheMountainUnderIt() {
        TerrainSample sample = sample(TIBET_METRES,
                column(WaterType.LAKE, ElevationProvider.NO_DATA, 0.0));

        // 5,440 m at 20 m per block is y=272. The collapse wrote rock to y=-1 and water at y=0.
        assertThat(sample.surfaceY()).isEqualTo(AUDITED_SCALE.toBlockY(TIBET_METRES));
        assertThat(sample.surfaceY()).isGreaterThan(270);
        assertThat(sample.waterType()).isEqualTo(WaterType.NONE);
    }

    @Test
    void aTibetanLakeDeclaredAtSeaLevelIsRejectedRatherThanBelieved() {
        TerrainSample sample = sample(TIBET_METRES, column(WaterType.LAKE, 0.0, 0.0));

        assertThat(sample.surfaceY()).isEqualTo(AUDITED_SCALE.toBlockY(TIBET_METRES));
        assertThat(sample.waterType()).isEqualTo(WaterType.NONE);
    }

    @Test
    void aTibetanLakeAtItsRealAltitudeHoldsWaterUpThere() {
        TerrainSample sample = sample(TIBET_METRES, column(WaterType.LAKE, TIBET_METRES, 60.0));

        assertThat(sample.waterType()).isEqualTo(WaterType.LAKE);
        assertThat(sample.waterSurfaceY()).isEqualTo(AUDITED_SCALE.toBlockY(TIBET_METRES));
        assertThat(sample.isUnderwater()).isTrue();
        // The bed is carved below the lake's own surface, not down to sea level.
        assertThat(sample.surfaceY()).isGreaterThan(AUDITED_SCALE.toBlockY(TIBET_METRES) - 5);
    }

    @Test
    void vanillaWorldgenIsOffUnlessThisWorldRunsInVanillasFrame() {
        // Defect B: with these on, vanilla's carvers and aquifer treat y=63 as sea level in a world
        // whose sea level is y=0, and flood carved rock 1,260 m above the sea they drained.
        TerraForgeChunkGenerator generator = generator(TerraForgeConfig.defaults());

        assertThat(generator.shouldGenerateCaves()).isFalse();
        assertThat(generator.shouldGenerateDecorations()).isFalse();
        assertThat(generator.shouldGenerateStructures()).isFalse();
        assertThat(generator.shouldGenerateNoise()).isFalse();
        assertThat(generator.shouldGenerateSurface()).isFalse();
    }

    @Test
    void terraForgesOwnCavesDoNotSwitchOnVanillasCarvers() {
        // The bug the audit named: generation.caves reached shouldGenerateCaves(), so the flag
        // documented as "TerraForge's karst caves" enabled vanilla's carvers instead.
        TerraForgeConfig withKarstCaves = withGeneration(new TerraForgeConfig.GenerationSection(
                true, true, false, false, false, 4, null));

        assertThat(generator(withKarstCaves).shouldGenerateCaves()).isFalse();
        assertThat(TerraForgeChunkGenerator.Features.from(withKarstCaves.generation()).karstCaves()).isTrue();
    }

    @Test
    void theFrameWarningNamesBothFramesAndTheDamageInRealMetres() {
        var mixed = new TerraForgeChunkGenerator.Features(false, true, false);

        String warning = TerraForgeChunkGenerator.vanillaFrameWarning(AUDITED_SCALE, mixed);

        // Substituted, not swallowed: System.Logger formats with MessageFormat, where the possessive
        // apostrophes in this sentence would quote away every placeholder after the first one.
        assertThat(warning).doesNotContain("{0}").doesNotContain("%d");
        assertThat(warning)
                .contains("sea level 0")
                .contains("min y -512")
                .contains("20.0 m per block")
                .contains("63/-64/1.0")
                .contains("600 m of real rock")
                .contains("up to y=63");
    }

    @Test
    void aWorldRunningInVanillasOwnFrameIsNotWarnedAbout() {
        var vanillaFrame = new VerticalScale(63, -64, 320, 1.0, 1.0);
        var mixed = new TerraForgeChunkGenerator.Features(false, true, true);

        // Vanilla's stages are exactly as correct here as they are in a vanilla world.
        assertThat(TerraForgeChunkGenerator.vanillaFrameWarning(vanillaFrame, mixed)).isNull();
        assertThat(TerraForgeChunkGenerator.vanillaFrameWarning(AUDITED_SCALE,
                new TerraForgeChunkGenerator.Features(true, false, false))).isNull();
    }

    // --- fixtures -----------------------------------------------------------

    private static TerrainSample sample(double demMetres, WaterProvider.WaterColumn water) {
        TerrainStack stack = TerrainStack.create(TerraForgeConfig.defaults(), transformer(), AUDITED_SCALE,
                flatElevation(demMetres), new CacheManager(64), waterProvider(water),
                ConstantLandcoverProvider.unknown());
        return stack.pipeline().sampleColumn(TIBET_LATITUDE, TIBET_LONGITUDE);
    }

    private static TerraForgeChunkGenerator generator(TerraForgeConfig config) {
        return TerrainStack.create(config, transformer(), AUDITED_SCALE, flatElevation(0.0),
                new CacheManager(64), waterProvider(WaterProvider.WaterColumn.DRY),
                ConstantLandcoverProvider.unknown()).chunkGenerator();
    }

    private static TerraForgeConfig withGeneration(TerraForgeConfig.GenerationSection generation) {
        TerraForgeConfig d = TerraForgeConfig.defaults();
        return new TerraForgeConfig(d.world(), d.scale(), d.earth(), d.terrain(), d.water(), d.biomes(),
                generation, d.pregeneration(), d.infrastructure(), d.data(), d.cache(), d.towny(),
                d.bluemap(), d.debug(), d.testRegion());
    }

    private static WaterProvider.WaterColumn column(WaterType type, double surfaceMetres, double bedDepth) {
        return new WaterProvider.WaterColumn(type, surfaceMetres, bedDepth);
    }

    private static CoordinateTransformer transformer() {
        return new CoordinateTransformer(EquirectangularProjection.plateCarree(),
                new GeoPoint(51.0, 10.0), 1.0);
    }

    private static WaterProvider waterProvider(WaterProvider.WaterColumn answer) {
        return new WaterProvider() {
            @Override
            public WaterType waterTypeAt(double latitude, double longitude) {
                return answer.type();
            }

            @Override
            public WaterColumn waterColumnAt(double latitude, double longitude, double knownElevationMeters) {
                return answer;
            }
        };
    }

    private static ElevationProvider flatElevation(double metres) {
        return new ElevationProvider() {
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
                return true;
            }
        };
    }
}
