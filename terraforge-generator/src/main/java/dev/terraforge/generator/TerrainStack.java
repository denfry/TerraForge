package dev.terraforge.generator;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.data.ConstantLandcoverProvider;
import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.data.LandcoverProvider;
import dev.terraforge.core.data.SeaLevelWaterProvider;
import dev.terraforge.core.data.WaterProvider;
import dev.terraforge.core.data.KarstProvider;
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
    private final CoordinateTransformer transformer;
    private final KarstProvider karst;

    private TerrainStack(DefaultTerrainPipeline pipeline, CachingChunkSampler chunkSampler,
                         VerticalScale verticalScale, BiomeMapper biomeMapper, TerraForgeConfig config,
                         CoordinateTransformer transformer, KarstProvider karst) {
        this.pipeline = pipeline;
        this.chunkSampler = chunkSampler;
        this.verticalScale = verticalScale;
        this.biomeMapper = biomeMapper;
        this.config = config;
        this.transformer = transformer;
        this.karst = karst;
    }

    /**
     * Builds the stack.
     *
     * <p>This convenience overload uses documented fallbacks. The overload taking water and
     * land-cover providers is used once prepared Phase 6 data is available.
     */
    public static TerrainStack create(TerraForgeConfig config, CoordinateTransformer transformer,
                                       VerticalScale verticalScale, ElevationProvider elevation,
                                       CacheManager cacheManager) {
        return create(config, transformer, verticalScale, elevation, cacheManager,
                new SeaLevelWaterProvider(elevation), ConstantLandcoverProvider.unknown());
    }

    /** Builds the stack with prepared Phase 6 providers, or documented fallbacks supplied by caller. */
    public static TerrainStack create(TerraForgeConfig config, CoordinateTransformer transformer,
                                      VerticalScale verticalScale, ElevationProvider elevation,
                                      CacheManager cacheManager, WaterProvider water,
                                      LandcoverProvider landcover) {
        return create(config, transformer, verticalScale, elevation, cacheManager, water, landcover, KarstProvider.absent());
    }
    public static TerrainStack create(TerraForgeConfig config, CoordinateTransformer transformer,
                                      VerticalScale verticalScale, ElevationProvider elevation,
                                      CacheManager cacheManager, WaterProvider water,
                                      LandcoverProvider landcover, KarstProvider karst) {

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
                new ClimateBiomeMapper(), config, transformer, karst);
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
                config.terrain().bedrockThickness(), config.vegetation().enabled(), config.generation().caves(),
                transformer, karst);
    }
}
