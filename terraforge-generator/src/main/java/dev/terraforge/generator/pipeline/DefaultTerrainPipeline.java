package dev.terraforge.generator.pipeline;

import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.data.LandcoverProvider;
import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.core.data.WaterProvider;
import dev.terraforge.core.data.WaterProvider.WaterColumn;
import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.core.terrain.Antarctica;
import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.core.terrain.ClimateBiomeResolver;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.core.terrain.VerticalScale;
import dev.terraforge.generator.noise.CellNoise;

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
    private final int minLakeDepthBlocks;
    private final int minRiverDepthBlocks;
    private final int minOceanDepthBlocks;
    /** Lattice spacing, in columns, of the bed-depth noise: a basin every dozen blocks or so. */
    private static final int DEPTH_NOISE_CELL = 12;
    private static final long DEPTH_NOISE_SALT = 0xBED0DE9700000001L;
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
        this.minLakeDepthBlocks = builder.minLakeDepthBlocks;
        this.minRiverDepthBlocks = builder.minRiverDepthBlocks;
        this.minOceanDepthBlocks = builder.minOceanDepthBlocks;
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
        return sampleColumn(latitude, longitude, 0.0, 0.0);
    }

    @Override
    public TerrainSample sampleColumn(double latitude, double longitude,
                                      double latitudeSpanDegrees, double longitudeSpanDegrees) {
        double rawMeters = elevation.averageElevationAt(
                latitude, longitude, latitudeSpanDegrees, longitudeSpanDegrees);
        boolean fallback = ElevationProvider.isNoData(rawMeters);
        double meters = fallback ? fallbackElevation : rawMeters;

        // Water is classified from the raw (possibly missing) elevation, never the substituted
        // fallback: a vector water provider (real coastlines, lakes, rivers) does not need a DEM at
        // all and must not be skipped just because the DEM has a hole. Only the elevation-derived
        // fallback provider actually consults the hint, and it already treats NaN as "unknown".
        WaterColumn column = water.waterColumnAt(latitude, longitude, rawMeters);
        WaterType waterType = enabledWaterType(column.type());
        double waterSurfaceMeters = column.surfaceElevationMeters();

        if (waterType == WaterType.RIVER && ElevationProvider.isNoData(waterSurfaceMeters)) {
            // A river's surface is the terrain height, and where the DEM has a hole that is the
            // substituted fallback -- not sea level, and not NaN. The column is already flagged.
            waterSurfaceMeters = meters;
        }
        if (waterType.isWater() && !placeable(waterType, waterSurfaceMeters, rawMeters,
                column.bedDepthMeters())) {
            // An unknown or implausible water surface means this water body cannot be placed here.
            // It must never fall back to sea level: doing so drags the whole column down to y=0,
            // which is how a previous build deleted 5.5 km of Tibetan plateau and left a sand lake
            // bed behind. Dry land is the honest answer; the biome still reflects the real climate.
            waterType = WaterType.NONE;
        }

        if (waterType == WaterType.RIVER || waterType == WaterType.LAKE) {
            // HydroRIVERS has a centreline and a discharge, HydroLAKES an average depth: neither is
            // bathymetry. The bed is carved below the water's own surface, so the surrounding real
            // valley -- and a mountain lake's real altitude -- stay intact.
            meters = waterSurfaceMeters - column.bedDepthMeters();
        }

        // Below the surface of the ocean the terrain is the sea bed. Without bathymetry the DEM
        // stops at the coast, so the configured default depth stands in for it -- and the column is
        // marked as a fallback, because that depth is a guess, not a measurement.
        if (waterType == WaterType.OCEAN && !elevation.hasBathymetry()) {
            meters = -defaultOceanDepth;
            fallback = true;
        }

        // The Antarctic ice sheet is drawn as a low, gentle dome rather than the kilometre-high
        // plateau the DEM would give at a vertically exaggerated scale. See Antarctica.
        if (!waterType.isWater() && Antarctica.isIceSheet(latitude)) {
            meters = Antarctica.flatten(meters);
        }

        LandcoverClass cover = landcover.landcoverAt(latitude, longitude);
        ClimateBiome biome = biomeResolver.resolve(latitude, meters, waterType, cover);

        int surfaceY = verticalScale.toBlockY(meters);
        int waterSurfaceY = waterType.isWater()
                ? verticalScale.toBlockY(waterSurfaceMeters)
                : verticalScale.seaLevel();
        if (waterType.isWater()) {
            // Water is wet. Rounding at coarse vertical scales otherwise puts the bed back level
            // with the surface -- a 5 m sea or a shallow river channel is less than one block at
            // 20 m per block -- and the generator then writes a dry basin where the map says water.
            // Bounded by this body's own surface, so it can only ever remove the single block the
            // vertical scale cannot represent, never a mountain: that was the previous build's bug,
            // and it came from a surface elevation of 0, not from this clamp.
            surfaceY = Math.min(surfaceY, waterSurfaceY - 1);
            // And it is deep enough to see. A real depth of a few metres rounds to nothing at a
            // coarse vertical scale, which left every lake one block deep over a flat floor. The
            // floor is a configured minimum per water type; a smooth, seedless noise over the
            // column grid deepens it by up to that much again, so beds are basins, not slabs.
            // Real bathymetry deeper than the floor is untouched: this only ever lowers the bed.
            int floor = minimumDepthBlocks(waterType);
            int extra = depthVariation(latitude, longitude, latitudeSpanDegrees, longitudeSpanDegrees, floor);
            surfaceY = Math.min(surfaceY, waterSurfaceY - floor - extra);
        }

        return new TerrainSample(meters, surfaceY, waterType, waterSurfaceY, cover, biome, fallback);
    }

    private int minimumDepthBlocks(WaterType type) {
        return switch (type) {
            case LAKE -> minLakeDepthBlocks;
            case RIVER -> minRiverDepthBlocks;
            case OCEAN -> minOceanDepthBlocks;
            case NONE -> 0;
        };
    }

    /**
     * Extra bed depth in {@code [0, floor]} from smooth noise over the column grid. The grid is the
     * footprint the sampler passes in; a direct call with no footprint gets no variation.
     */
    static int depthVariation(double latitude, double longitude, double latitudeSpan, double longitudeSpan,
                              int floor) {
        if (floor <= 0 || latitudeSpan <= 0.0 || longitudeSpan <= 0.0) {
            return 0;
        }
        int row = (int) Math.floor(latitude / latitudeSpan);
        int col = (int) Math.floor(longitude / longitudeSpan);
        double noise = CellNoise.smooth(col, row, DEPTH_NOISE_CELL, DEPTH_NOISE_SALT);
        return (int) Math.round(noise * floor);
    }

    private WaterType enabledWaterType(WaterType type) {
        return switch (type) {
            case OCEAN -> oceansEnabled ? type : WaterType.NONE;
            case LAKE -> lakesEnabled ? type : WaterType.NONE;
            case RIVER -> riversEnabled ? type : WaterType.NONE;
            case NONE -> WaterType.NONE;
        };
    }

    /**
     * Whether a declared water surface can be placed at this column.
     *
     * <p>Unknown surface, no water: that is the whole rule for the ocean (whose surface is sea level
     * by definition) and for a river (whose surface is the terrain height).
     *
     * <p>A lake gets one more test, because a lake is the only water body that carries an absolute
     * altitude from its source and the only one a DEM can contradict. A DEM measures a lake at its
     * water surface, so on real data the two agree to a few tens of metres; a disagreement of
     * kilometres means the source's elevation attribute is wrong, or the polygon covers ground that
     * is not the lake at this resolution. Honouring such a value would carve -- or flood -- the
     * column by that entire difference, a mountain-sized edit driven by one bad number in a file.
     */
    private static boolean placeable(WaterType waterType, double surfaceMeters, double demMeters,
                                     double bedDepthMeters) {
        if (ElevationProvider.isNoData(surfaceMeters)) {
            return false;
        }
        if (waterType != WaterType.LAKE || ElevationProvider.isNoData(demMeters)) {
            // No DEM to cross-check against; the source's own value is all there is.
            return true;
        }
        return Math.abs(surfaceMeters - demMeters) <= bedDepthMeters + LAKE_SURFACE_AGREEMENT_METERS;
    }

    /**
     * How far a lake's declared surface may sit from the DEM at the same column before the lake is
     * rejected. Generous enough for real disagreement between a lake register and a DEM (tens of
     * metres), and for a block whose footprint straddles a shoreline at a coarse scale; far too
     * small to let a sea-level default stand in for a Himalayan lake.
     */
    private static final double LAKE_SURFACE_AGREEMENT_METERS = 250.0;

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
        private int minLakeDepthBlocks = 1;
        private int minRiverDepthBlocks = 1;
        private int minOceanDepthBlocks = 1;
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

        /** Depth floors in blocks per water type; each at least 1. Default 1: no floor beyond wet. */
        public Builder minimumDepthBlocks(int lake, int river, int ocean) {
            if (lake < 1 || river < 1 || ocean < 1) {
                throw new IllegalArgumentException("minimum depths must be at least 1 block");
            }
            this.minLakeDepthBlocks = lake;
            this.minRiverDepthBlocks = river;
            this.minOceanDepthBlocks = ocean;
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
