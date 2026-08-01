package dev.terraforge.generator.pipeline;

import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.data.LandcoverProvider;
import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.core.data.WaterProvider;
import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.core.terrain.ClimateBiomeResolver;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.core.terrain.VerticalScale;

/**
 * Resolves one column of terrain from prepared geodata.
 *
 * <p>The stages are exactly those listed in {@link TerrainPipeline}, and every one of them reads
 * real data or a documented fallback -- there is no noise function anywhere in this class. A column
 * looks the way it does because the Earth looks that way.
 *
 * <p>Fallbacks are recorded rather than hidden: a sample built on substituted elevation carries
 * {@link TerrainSample#fromFallback()}, so the debug overlay and the pregenerator can tell real
 * terrain from filler.
 *
 * <p>Immutable and thread-safe, given thread-safe providers.
 */
public final class DefaultTerrainPipeline implements TerrainPipeline {

    private final ElevationProvider elevation;
    private final WaterProvider water;
    private final LandcoverProvider landcover;
    private final ClimateBiomeResolver biomeResolver;
    private final VerticalScale verticalScale;
    private final ChunkSampler chunkSampler;

    private final double fallbackElevation;
    private final double defaultOceanDepth;
    private final boolean oceansEnabled;
    private final boolean lakesEnabled;
    private final boolean riversEnabled;

    private DefaultTerrainPipeline(Builder builder) {
        this.elevation = builder.elevation;
        this.water = builder.water;
        this.landcover = builder.landcover;
        this.biomeResolver = builder.biomeResolver;
        this.verticalScale = builder.verticalScale;
        this.fallbackElevation = builder.fallbackElevation;
        this.defaultOceanDepth = builder.defaultOceanDepth;
        this.oceansEnabled = builder.oceansEnabled;
        this.lakesEnabled = builder.lakesEnabled;
        this.riversEnabled = builder.riversEnabled;
        this.chunkSampler = builder.chunkSamplerFactory.apply(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public VerticalScale verticalScale() {
        return verticalScale;
    }

    @Override
    public TerrainSample sampleColumn(double latitude, double longitude) {
        double meters = elevation.elevationAt(latitude, longitude);
        boolean fallback = ElevationProvider.isNoData(meters);
        if (fallback) {
            meters = fallbackElevation;
        }

        // The elevation is passed on rather than looked up again: the fallback water provider is
        // derived from it, and a second DEM query per column would double the cost of generation.
        WaterType waterType = classifyWater(latitude, longitude, meters, fallback);
        double waterSurfaceMeters = waterSurface(latitude, longitude, meters, waterType);

        if (waterType == WaterType.RIVER) {
            // HydroRIVERS has a centreline and discharge, not bathymetry. The provider gives the
            // prepared width-derived depth; preserve the DEM height as the water surface and carve
            // only the shallow channel so the surrounding real valley stays intact.
            meters -= water.riverBedDepthMeters(latitude, longitude, meters);
        }

        // Below the surface of a water body the terrain is the sea or lake bed. Without bathymetry
        // the DEM stops at the coast, so the configured default depth stands in for it -- and the
        // column is marked as a fallback, because that depth is a guess, not a measurement.
        if (waterType == WaterType.OCEAN && !elevation.hasBathymetry()) {
            meters = -defaultOceanDepth;
            fallback = true;
        }

        LandcoverClass cover = landcover.landcoverAt(latitude, longitude);
        ClimateBiome biome = biomeResolver.resolve(latitude, meters, waterType, cover);

        int surfaceY = verticalScale.toBlockY(meters);
        int waterSurfaceY = waterType.isWater()
                ? verticalScale.toBlockY(waterSurfaceMeters)
                : verticalScale.seaLevel();
        if (waterType == WaterType.RIVER) {
            // Rounding at coarse vertical scales must not put the carved bed back level with water.
            surfaceY = Math.min(surfaceY, waterSurfaceY - 1);
        }

        return new TerrainSample(meters, surfaceY, waterType, waterSurfaceY, cover, biome, fallback);
    }

    private WaterType classifyWater(double latitude, double longitude, double elevationMeters,
                                    boolean elevationMissing) {
        // An unprepared column has no evidence of water; flooding it would turn every coverage gap
        // into an ocean.
        if (elevationMissing) {
            return WaterType.NONE;
        }
        WaterType type = water.waterTypeAt(latitude, longitude, elevationMeters);
        return switch (type) {
            case OCEAN -> oceansEnabled ? type : WaterType.NONE;
            case LAKE -> lakesEnabled ? type : WaterType.NONE;
            case RIVER -> riversEnabled ? type : WaterType.NONE;
            case NONE -> WaterType.NONE;
        };
    }

    private double waterSurface(double latitude, double longitude, double elevationMeters,
                                WaterType waterType) {
        if (!waterType.isWater()) {
            return 0.0;
        }
        double surface = water.waterSurfaceElevation(latitude, longitude, elevationMeters);
        // Lakes sit at their own altitude; where that is unknown, sea level is the only defensible
        // assumption.
        return ElevationProvider.isNoData(surface) ? 0.0 : surface;
    }

    @Override
    public ChunkSampler.ChunkSamples sampleChunk(int chunkX, int chunkZ) {
        return chunkSampler.sample(chunkX, chunkZ);
    }

    @Override
    public void invalidate() {
        if (chunkSampler instanceof CachingChunkSampler caching) {
            caching.invalidate();
        }
    }

    /** Builds a pipeline; every dependency is required except the water and landcover fallbacks. */
    public static final class Builder {

        private ElevationProvider elevation;
        private WaterProvider water;
        private LandcoverProvider landcover = dev.terraforge.core.data.ConstantLandcoverProvider.unknown();
        private ClimateBiomeResolver biomeResolver = new ClimateBiomeResolver();
        private VerticalScale verticalScale;
        private java.util.function.Function<TerrainPipeline, ChunkSampler> chunkSamplerFactory;

        private double fallbackElevation;
        private double defaultOceanDepth = 30.0;
        private boolean oceansEnabled = true;
        private boolean lakesEnabled = true;
        private boolean riversEnabled = true;

        public Builder elevation(ElevationProvider value) {
            this.elevation = value;
            return this;
        }

        public Builder water(WaterProvider value) {
            this.water = value;
            return this;
        }

        public Builder landcover(LandcoverProvider value) {
            this.landcover = value;
            return this;
        }

        public Builder biomeResolver(ClimateBiomeResolver value) {
            this.biomeResolver = value;
            return this;
        }

        public Builder verticalScale(VerticalScale value) {
            this.verticalScale = value;
            return this;
        }

        /** The sampler is built last because it needs the finished pipeline to sample with. */
        public Builder chunkSampler(java.util.function.Function<TerrainPipeline, ChunkSampler> factory) {
            this.chunkSamplerFactory = factory;
            return this;
        }

        public Builder fallbackElevation(double meters) {
            this.fallbackElevation = meters;
            return this;
        }

        public Builder defaultOceanDepth(double meters) {
            this.defaultOceanDepth = meters;
            return this;
        }

        public Builder waterFeatures(boolean oceans, boolean lakes, boolean rivers) {
            this.oceansEnabled = oceans;
            this.lakesEnabled = lakes;
            this.riversEnabled = rivers;
            return this;
        }

        public DefaultTerrainPipeline build() {
            if (elevation == null || verticalScale == null || chunkSamplerFactory == null) {
                throw new IllegalStateException(
                        "elevation, verticalScale and chunkSampler are required");
            }
            if (water == null) {
                water = new dev.terraforge.core.data.SeaLevelWaterProvider(elevation);
            }
            return new DefaultTerrainPipeline(this);
        }
    }
}
