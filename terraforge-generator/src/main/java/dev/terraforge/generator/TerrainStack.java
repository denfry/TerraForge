package dev.terraforge.generator;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.data.ConstantLandcoverProvider;
import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.data.LandcoverProvider;
import dev.terraforge.core.data.SeaLevelWaterProvider;
import dev.terraforge.core.data.WaterProvider;
import dev.terraforge.core.terrain.ClimateBiomeResolver;
import dev.terraforge.core.terrain.VerticalScale;
import dev.terraforge.generator.biome.BiomeMapper;
import dev.terraforge.generator.biome.ClimateBiomeMapper;
import dev.terraforge.generator.pipeline.CachingChunkSampler;
import dev.terraforge.generator.pipeline.DefaultTerrainPipeline;

/**
 * Assembles the terrain stack from configuration and the prepared data providers.
 *
 * <p>One place that knows how the parts fit together, so the Paper bootstrap does not have to, and
 * so a test or the CLI can build the identical stack without a server. Which providers are real and
 * which are documented fallbacks is decided here and nowhere else.
 */
public final class TerrainStack {

    private final DefaultTerrainPipeline pipeline;
    private final CachingChunkSampler chunkSampler;
    private final VerticalScale verticalScale;
    private final BiomeMapper biomeMapper;
    private final TerraForgeConfig config;

    private TerrainStack(DefaultTerrainPipeline pipeline, CachingChunkSampler chunkSampler,
                         VerticalScale verticalScale, BiomeMapper biomeMapper, TerraForgeConfig config) {
        this.pipeline = pipeline;
        this.chunkSampler = chunkSampler;
        this.verticalScale = verticalScale;
        this.biomeMapper = biomeMapper;
        this.config = config;
    }

    /**
     * Builds the stack.
     *
     * <p>Water and land cover default to the elevation-derived fallbacks until Phase 6 imports the
     * real datasets; passing real providers here is the only change that will need.
     */
    public static TerrainStack create(TerraForgeConfig config, CoordinateTransformer transformer,
                                      VerticalScale verticalScale, ElevationProvider elevation,
                                      CacheManager cacheManager) {
        WaterProvider water = new SeaLevelWaterProvider(elevation);
        LandcoverProvider landcover = ConstantLandcoverProvider.unknown();

        var samplerHolder = new java.util.concurrent.atomic.AtomicReference<CachingChunkSampler>();
        DefaultTerrainPipeline pipeline = DefaultTerrainPipeline.builder()
                .elevation(elevation)
                .water(water)
                .landcover(landcover)
                .biomeResolver(new ClimateBiomeResolver())
                .verticalScale(verticalScale)
                .fallbackElevation(config.terrain().fallbackElevation())
                .defaultOceanDepth(config.water().defaultOceanDepth())
                .waterFeatures(config.water().oceans(), config.water().lakes(), config.water().rivers())
                .chunkSampler(builtPipeline -> {
                    var sampler = new CachingChunkSampler(builtPipeline, transformer, cacheManager,
                            config.cache().chunkCacheEntries());
                    samplerHolder.set(sampler);
                    return sampler;
                })
                .build();

        return new TerrainStack(pipeline, samplerHolder.get(), verticalScale,
                new ClimateBiomeMapper(), config);
    }

    public DefaultTerrainPipeline pipeline() {
        return pipeline;
    }

    public CachingChunkSampler chunkSampler() {
        return chunkSampler;
    }

    /** A chunk generator bound to this stack; one per world. */
    public TerraForgeChunkGenerator chunkGenerator() {
        return new TerraForgeChunkGenerator(pipeline, verticalScale, biomeMapper,
                config.terrain().bedrockThickness(), config.vegetation().enabled());
    }
}
