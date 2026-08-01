package dev.terraforge.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.GeoPoint;

/**
 * Typed view of {@code terraforge.yml}.
 *
 * <p>Every field here is honoured by the runtime -- there are no decorative options. Records are
 * immutable, so a reload swaps the whole config atomically instead of mutating shared state.
 *
 * <p>Deserialised with Jackson using kebab-case naming, matching the YAML file verbatim.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TerraForgeConfig(
        WorldSection world,
        ScaleSection scale,
        EarthSection earth,
        TerrainSection terrain,
        WaterSection water,
        BiomesSection biomes,
        VegetationSection vegetation,
        GenerationSection generation,
        InfrastructureSection infrastructure,
        DataSection data,
        CacheSection cache,
        TownySection towny,
        BlueMapSection bluemap,
        DebugSection debug,
        TestRegionSection testRegion) {

    public static TerraForgeConfig defaults() {
        return new TerraForgeConfig(
                new WorldSection("earth"),
                new ScaleSection(1.0),
                new EarthSection(new OriginSection(51.0, 10.0), "equirectangular"),
                new TerrainSection(63, -64, 320, 1.0, 1.0, 0.0, 8),
                new WaterSection(true, true, true, 30.0),
                new BiomesSection(true, 0.35),
                new VegetationSection(true, 1.0),
                new GenerationSection(true, false, false, 4),
                InfrastructureSection.allDisabled(),
                new DataSection("data", "cache", "terraforge.db"),
                new CacheSection(1024, 256, DEFAULT_LANDCOVER_GRID_CACHE_ENTRIES, 4096, 300),
                new TownySection(true),
                new BlueMapSection(true, true, true),
                new DebugSection(false, false),
                TestRegionSection.centralEurope());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorldSection(String name) {
    }

    /** Horizontal scale. 1.0 means one block covers one kilometre of ground. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScaleSection(double blocksPerKm) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EarthSection(OriginSection origin, String projection) {
        public GeoPoint originPoint() {
            return new GeoPoint(origin.latitude(), origin.longitude());
        }
    }

    /** Geographic point that maps to Minecraft (0, 0). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OriginSection(double latitude, double longitude) {
    }

    /**
     * @param seaLevel             Minecraft Y of sea level
     * @param minY                 world bottom
     * @param maxY                 world top
     * @param verticalExaggeration multiplier applied to real elevation
     * @param metersPerBlock       vertical metres represented by one block
     * @param fallbackElevation    elevation used when no DEM tile covers a point
     * @param bedrockThickness     blocks of bedrock at the world bottom
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TerrainSection(
            int seaLevel,
            int minY,
            int maxY,
            double verticalExaggeration,
            double metersPerBlock,
            double fallbackElevation,
            int bedrockThickness) {
    }

    /** @param defaultOceanDepth depth in metres used where bathymetry is unavailable */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WaterSection(boolean oceans, boolean lakes, boolean rivers, double defaultOceanDepth) {
    }

    /** @param edgeNoise strength of the dithering applied to biome borders (0 disables it) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BiomesSection(boolean enabled, double edgeNoise) {
    }

    /** @param density multiplier on natural vegetation density (trees, grass, flowers) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VegetationSection(boolean enabled, double density) {
    }

    /**
     * @param naturalOnly     retained for existing configuration files; TerraForge never generates
     *                        player infrastructure.
     * @param caves           enables generated geometry constrained by prepared karst data. It is
     *                        off by default so existing worlds never change unexpectedly.
     * @param manMadeStructures enables vanilla's mixed structure pass. This is off by default and
     *                        is not used while natural-only is enabled.
     * @param workerThreads   threads used for asynchronous chunk data preparation
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenerationSection(boolean naturalOnly, boolean caves, boolean manMadeStructures,
                                    int workerThreads) {
    }

    /**
     * Hard switches for man-made features. All default to false and the generator has no code path
     * that builds them; the section exists so the guarantee is explicit and auditable.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InfrastructureSection(
            boolean roads,
            boolean buildings,
            boolean railways,
            boolean bridges,
            boolean airports,
            boolean powerLines) {

        public static InfrastructureSection allDisabled() {
            return new InfrastructureSection(false, false, false, false, false, false);
        }

        public boolean anyEnabled() {
            return roads || buildings || railways || bridges || airports || powerLines;
        }
    }

    /** Filesystem layout, relative to the plugin data folder. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DataSection(String dataDirectory, String cacheDirectory, String databaseFile) {
    }

    /**
     * @param memoryLimitMb        soft ceiling for all in-memory caches
     * @param demTileCacheEntries  DEM tiles kept resident
     * @param landcoverGridCacheEntries prepared land-cover grids kept resident
     * @param chunkCacheEntries    prepared chunk samples kept resident
     * @param statisticsIntervalSeconds how often cache statistics are logged in debug mode
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CacheSection(
            int memoryLimitMb,
            int demTileCacheEntries,
            int landcoverGridCacheEntries,
            int chunkCacheEntries,
            int statisticsIntervalSeconds) {

        /**
         * A config written before this key existed loads with land cover unbounded, which is exactly
         * the behaviour that made a planet-wide world impossible. Absent means default, not zero.
         */
        public CacheSection {
            if (landcoverGridCacheEntries <= 0) {
                landcoverGridCacheEntries = DEFAULT_LANDCOVER_GRID_CACHE_ENTRIES;
            }
        }
    }

    /** Enough for a 5x5 degree working area at the default grid size, about 92 MiB. */
    public static final int DEFAULT_LANDCOVER_GRID_CACHE_ENTRIES = 256;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TownySection(boolean enabled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BlueMapSection(boolean enabled, boolean cityMarkers, boolean countryLabels) {
    }

    /** @param perPlayer when true, players may toggle their own debug overlay */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DebugSection(boolean enabled, boolean perPlayer) {
    }

    /** The bounded region to develop and validate against before scaling to the planet. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestRegionSection(
            String name,
            double latitudeMin,
            double latitudeMax,
            double longitudeMin,
            double longitudeMax) {

        public static TestRegionSection centralEurope() {
            return new TestRegionSection("central-europe", 47.0, 55.5, 5.0, 15.5);
        }

        public GeoBounds toBounds() {
            return new GeoBounds(latitudeMin, longitudeMin, latitudeMax, longitudeMax);
        }
    }
}
