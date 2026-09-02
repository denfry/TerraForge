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
        GenerationSection generation,
        PregenerationSection pregeneration,
        InfrastructureSection infrastructure,
        DataSection data,
        CacheSection cache,
        TownySection towny,
        BlueMapSection bluemap,
        DebugSection debug,
        TestRegionSection testRegion) {

    /** Keeps configuration files written before pregeneration limits compatible and safe. */
    public TerraForgeConfig {
        if (pregeneration == null) {
            pregeneration = PregenerationSection.defaults();
        }
    }

    public static TerraForgeConfig defaults() {
        return new TerraForgeConfig(
                new WorldSection("earth"),
                new ScaleSection(1.0),
                new EarthSection(new OriginSection(51.0, 10.0), "equirectangular"),
                new TerrainSection(63, -64, 320, 1.0, 1.0, 0.0, 8),
                new WaterSection(true, true, true, 30.0, 1.0),
                new BiomesSection(true, 0.35),
                new GenerationSection(true, false, false, false, false, 4),
                PregenerationSection.defaults(),
                InfrastructureSection.allDisabled(),
                new DataSection("data", "cache", "terraforge.db"),
                new CacheSection(1024, 256, DEFAULT_LANDCOVER_GRID_CACHE_ENTRIES,
                        DEFAULT_WATER_FEATURE_CACHE_ENTRIES, 4096, 300),
                new TownySection(true),
                new BlueMapSection(true, true, true, 300, 5_000),
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

    /**
     * @param defaultOceanDepth          depth in metres used where bathymetry is unavailable
     * @param minVisibleRiverDischargeCms HydroRIVERS long-term mean discharge, in cubic metres per
     *                                    second, below which a prepared river line is not carved.
     *                                    The row stays in the database either way -- this only
     *                                    controls what the current world renders, so raising it later
     *                                    needs no re-preparation. 0 renders every prepared river,
     *                                    including the smallest headwaters HydroRIVERS maps.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WaterSection(boolean oceans, boolean lakes, boolean rivers, double defaultOceanDepth,
                                double minVisibleRiverDischargeCms) {
    }

    /** @param edgeNoise strength of the dithering applied to biome borders (0 disables it) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BiomesSection(boolean enabled, double edgeNoise) {
    }

    /**
     * @param naturalOnly       retained for existing configuration files; TerraForge never generates
     *                          player infrastructure.
     * @param caves             enables TerraForge's own cave geometry, constrained by prepared WOKAM
     *                          karst data and anchored at prepared OSM cave entrances. Off by
     *                          default so existing worlds never change unexpectedly.
     * @param vanillaCaves      hands each chunk to vanilla's cave and canyon carvers <em>and</em> its
     *                          aquifer. Off by default, and it should stay off unless this world's
     *                          vertical frame is vanilla's: those stages work in vanilla's own frame
     *                          (min y -64, sea level 63, one metre per block) and know nothing of
     *                          {@code terrain.*}. In a 20 m-per-block world a routine 30-block cave
     *                          deletes 600 m of real rock, vanilla's carvers are allowed to replace
     *                          water, and the aquifer refills whatever they breach up to y=63.
     * @param vanillaDecorations hands each chunk to vanilla's decoration pass. All of it or none of
     *                          it: trees, grass and flowers come with ore veins, {@code spring_lava},
     *                          {@code lake_lava} and kelp, placed at vanilla's density in whatever
     *                          vertical frame this world uses. Off by default for the same reason.
     * @param manMadeStructures enables vanilla's mixed structure pass. This is off by default and
     *                          is not used while natural-only is enabled.
     * @param workerThreads     threads used for asynchronous chunk data preparation
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GenerationSection(boolean naturalOnly, boolean caves, boolean vanillaCaves,
                                    boolean vanillaDecorations, boolean manMadeStructures,
                                    int workerThreads) {
    }

    /** Limits that keep disk-backed pregeneration bounded and safe to resume. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PregenerationSection(int maxInFlight, boolean pauseWhenPlayersOnline,
                                       double minimumTps, double maximumMspt, int stableResumeSeconds,
                                       long minimumFreeDiskGb, int checkpointEveryChunks) {
        public static PregenerationSection defaults() {
            return new PregenerationSection(1, true, 18.0, 40.0, 15, 10, 128);
        }
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
     * @param waterFeatureCacheEntries decoded natural-water geometries kept resident
     * @param chunkCacheEntries    prepared chunk samples kept resident
     * @param statisticsIntervalSeconds how often cache statistics are logged in debug mode
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CacheSection(
            int memoryLimitMb,
            int demTileCacheEntries,
            int landcoverGridCacheEntries,
            int waterFeatureCacheEntries,
            int chunkCacheEntries,
            int statisticsIntervalSeconds) {

        /**
         * A config written before one of these keys existed loads with that cache unbounded, which is
         * exactly the behaviour that made a planet-wide world impossible. Absent means default, not
         * zero.
         */
        public CacheSection {
            if (landcoverGridCacheEntries <= 0) {
                landcoverGridCacheEntries = DEFAULT_LANDCOVER_GRID_CACHE_ENTRIES;
            }
            if (waterFeatureCacheEntries <= 0) {
                waterFeatureCacheEntries = DEFAULT_WATER_FEATURE_CACHE_ENTRIES;
            }
        }
    }

    /** Enough for a 5x5 degree working area at the default grid size, about 92 MiB. */
    public static final int DEFAULT_LANDCOVER_GRID_CACHE_ENTRIES = 256;

    /** Enough decoded water geometries to cover a working area without re-decoding on every visit. */
    public static final int DEFAULT_WATER_FEATURE_CACHE_ENTRIES = 4096;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TownySection(boolean enabled) {
    }

    /**
     * @param maxCityMarkers    upper bound on non-capital city markers published to the map;
     *                          capitals are always kept regardless of this cap
     * @param minCityPopulation smallest population a non-capital city needs to be published; raise
     *                          this to drop the flood of small-town markers that make a dense
     *                          gazetteer unusable (and slow) in a web browser
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BlueMapSection(boolean enabled, boolean cityMarkers, boolean countryLabels,
                                 int maxCityMarkers, long minCityPopulation) {

        /** Matches the historical unlimited-ish default so old configs missing this key keep working. */
        public static final int DEFAULT_MAX_CITY_MARKERS = 2_000;

        public BlueMapSection {
            if (maxCityMarkers <= 0) {
                maxCityMarkers = DEFAULT_MAX_CITY_MARKERS;
            }
            if (minCityPopulation < 0) {
                minCityPopulation = 0;
            }
        }
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
