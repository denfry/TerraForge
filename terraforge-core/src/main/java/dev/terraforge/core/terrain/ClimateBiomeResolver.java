package dev.terraforge.core.terrain;

import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.core.data.WaterProvider.WaterType;

/**
 * Resolves a real-world biome from latitude, elevation, water and land cover.
 *
 * <p>A pure function of its inputs -- no noise, no seed, no randomness. The same point on Earth
 * always yields the same biome, which is the whole promise of the project: Bavaria is forest
 * because Bavaria <em>is</em> forest, not because a noise field said so.
 *
 * <p>The model has two layers. Land cover, when the data provides it, decides <em>what grows</em>.
 * Latitude and elevation decide <em>which variety</em> of it -- the same tree cover is tropical
 * rainforest at the equator, temperate forest in Germany and boreal forest in Finland. When land
 * cover is unknown the latitude band alone is used, which is coarse but never wrong about the
 * hemisphere.
 *
 * <p>Immutable and thread-safe.
 */
public final class ClimateBiomeResolver {

    /** Tropics: the latitude of the Tropic of Cancer/Capricorn. */
    private static final double TROPICS = 23.5;
    /** Polar circles. */
    private static final double POLAR = 66.5;
    /** Above this depth the ocean floor is shelf rather than abyss, in metres below sea level. */
    private static final double DEEP_OCEAN_DEPTH = 1000.0;
    /** Land at or below this elevation is coastal, in metres. */
    private static final double SHORE_ELEVATION = 3.0;

    /** Lowest elevation at which mountain varieties of a biome appear, in metres. */
    private static final double MOUNTAIN_ELEVATION = 800.0;
    /** Blocks of elevation above the treeline that are alpine mat before turning to bare rock. */
    private static final double ALPINE_BAND = 800.0;
    /**
     * Floor under the snowline. Past the polar circles the linear fit would put permanent snow at
     * sea level, which would bury the Arctic tundra in ice it does not have.
     */
    private static final double MIN_SNOWLINE = 300.0;

    /** Latitudes of the treeline and snowline tables below, in degrees from the equator. */
    private static final double[] LINE_LATITUDES = {0, 15, 25, 30, 35, 40, 46, 50, 55, 60, 65, 70, 75, 80, 90};
    /**
     * Treeline in metres at {@link #LINE_LATITUDES}: about 3900 m in the tropics, highest in the
     * subtropical highlands, 2100 m in the Alps, 800 m in Norway, nothing past 70 degrees.
     */
    private static final double[] TREELINE = {3900, 4100, 4000, 3600, 3100, 2700, 2100, 1700, 1100, 800, 400, 0, 0, 0, 0};
    /**
     * Permanent snow in metres at {@link #LINE_LATITUDES}: lower on the wet equator than in the dry
     * subtropics, where the Andes and the Himalaya keep it above 5000 m; about 3000 m in the Alps,
     * 1500 m in Scandinavia. A straight line through the poles and the equator put glaciers on the
     * Drakensberg and the Alps a kilometre too low.
     */
    private static final double[] SNOWLINE = {4800, 5000, 5500, 5300, 4600, 3900, 3000, 2600, 2000, 1500, 1100, 700, 400, MIN_SNOWLINE, MIN_SNOWLINE};

    /** How far the snowline wanders around its table value with aspect and shelter, in metres. */
    private static final double SNOWLINE_WANDER = 350.0;
    /** How far the treeline wanders, in metres. */
    private static final double TREELINE_WANDER = 250.0;
    /** How far the shelf break wanders around {@link #DEEP_OCEAN_DEPTH}, in metres. */
    private static final double SHELF_WANDER = 400.0;
    /** How far the edge of the warm ocean wanders around the tropics, in degrees. */
    private static final double TROPICS_WANDER = 3.0;
    /** Coarse cell of the wandering, in degrees: about 30 km. */
    private static final double WANDER_CELL = 0.3;

    /** Elevation above which trees stop, in metres. */
    public static double treelineMeters(double latitude) {
        return lookup(TREELINE, Math.abs(latitude));
    }

    /** Elevation above which snow is permanent, in metres. */
    public static double snowlineMeters(double latitude) {
        return Math.max(MIN_SNOWLINE, lookup(SNOWLINE, Math.abs(latitude)));
    }

    private static double lookup(double[] values, double absLatitude) {
        for (int i = 1; i < LINE_LATITUDES.length; i++) {
            if (absLatitude <= LINE_LATITUDES[i]) {
                double t = (absLatitude - LINE_LATITUDES[i - 1]) / (LINE_LATITUDES[i] - LINE_LATITUDES[i - 1]);
                return values[i - 1] + (values[i] - values[i - 1]) * t;
            }
        }
        return values[values.length - 1];
    }

    /** A threshold's wander at this place, {@code 0} when the longitude is unknown. */
    private static double wander(double latitude, double longitude, double amount, long salt) {
        if (Double.isNaN(longitude)) {
            return 0.0;
        }
        return GeoNoise.at(latitude, longitude, WANDER_CELL, salt) * amount;
    }

    /**
     * @param latitude       degrees; sign is ignored, climate is symmetric about the equator
     * @param elevationMeters real elevation above sea level
     * @param water          water classification of the column
     * @param landcover      land cover class, {@link LandcoverClass#UNKNOWN} when unavailable
     */
    public ClimateBiome resolve(double latitude, double elevationMeters,
                                WaterType water, LandcoverClass landcover) {
        return resolve(latitude, Double.NaN, elevationMeters, water, landcover);
    }

    /**
     * As {@link #resolve(double, double, WaterType, LandcoverClass)}, with the snowline, treeline,
     * shelf break and edge of the tropics wandering around their table values by place, so the
     * boundaries between biomes are ragged rather than a contour line or a parallel.
     *
     * @param longitude degrees; {@link Double#NaN} for the exact table values
     */
    public ClimateBiome resolve(double latitude, double longitude, double elevationMeters,
                                WaterType water, LandcoverClass landcover) {
        if (water.isWater()) {
            return resolveWater(latitude, longitude, elevationMeters, water);
        }
        double absLatitude = Math.abs(latitude);

        // The Antarctic ice sheet is ice sheet to the water's edge: no shore, no tundra, no rock.
        if (Antarctica.isIceSheet(latitude)) {
            return ClimateBiome.GLACIER;
        }
        if (landcover == LandcoverClass.SNOW_ICE) {
            return ClimateBiome.GLACIER;
        }
        // The shore is decided before the mountain rules: a glacier can reach the sea, but the strip
        // where land meets water is still a shore, and it is what the player walks onto.
        if (elevationMeters <= SHORE_ELEVATION) {
            return absLatitude >= POLAR ? ClimateBiome.STONY_SHORE : ClimateBiome.BEACH;
        }
        if (elevationMeters > snowlineMeters(latitude) + wander(latitude, longitude, SNOWLINE_WANDER, 0x534E4F57L)) {
            return ClimateBiome.GLACIER;
        }
        // Beyond the polar circles there are no trees at any elevation, so the treeline says
        // nothing there: the land is tundra until it is permanent ice.
        if (absLatitude >= POLAR) {
            return landcover == LandcoverClass.BARE_SPARSE ? ClimateBiome.BARE_ROCK : ClimateBiome.TUNDRA;
        }
        double treeline = treelineMeters(latitude) + wander(latitude, longitude, TREELINE_WANDER, 0x54524545L);
        if (elevationMeters > treeline) {
            // Above the treeline the surface is alpine mat, then bare rock further up.
            return elevationMeters > treeline + ALPINE_BAND ? ClimateBiome.BARE_ROCK : ClimateBiome.ALPINE;
        }
        // Mountain varieties need both a real altitude and proximity to the treeline, so a plain at
        // 60 degrees north does not become a mountain just because its treeline is low.
        // The montane belt is about a kilometre deep: 1100..2100 m in the Alps.
        boolean mountainous = elevationMeters > MOUNTAIN_ELEVATION && elevationMeters > treeline - 1000.0;

        return switch (landcover) {
            case TREE_COVER -> mountainous ? ClimateBiome.MOUNTAIN_FOREST : forestFor(absLatitude);
            case MANGROVES -> ClimateBiome.MANGROVE;
            case HERBACEOUS_WETLAND -> ClimateBiome.WETLAND;
            case SHRUBLAND -> absLatitude >= POLAR ? ClimateBiome.TUNDRA : ClimateBiome.SHRUBLAND;
            case GRASSLAND, CROPLAND -> grasslandFor(absLatitude, mountainous);
            case MOSS_LICHEN -> ClimateBiome.TUNDRA;
            case BARE_SPARSE -> absLatitude < 35.0 ? ClimateBiome.DESERT : ClimateBiome.BARE_ROCK;
            // Handled above, before the treeline rules; listed so the switch stays exhaustive.
            case SNOW_ICE -> ClimateBiome.GLACIER;
            // Built-up land is read from the data and mapped to what surrounds it. TerraForge never
            // generates the settlement the source dataset saw -- players build those.
            //
            // PERMANENT_WATER falls back the same way rather than claiming a lake: this landcover
            // class alone is not vetted water evidence (the vector WaterProvider already returned
            // NONE for this column, or resolveWater() would have run instead), and ESA WorldCover is
            // known to misclassify bright arid ground -- salt pans, playas -- as permanent water. An
            // unconfirmed pixel should not paint dry land with a lake biome and palette.
            case BUILT_UP, UNKNOWN, PERMANENT_WATER ->
                    mountainous ? ClimateBiome.MOUNTAIN_FOREST : forestFor(absLatitude);
        };
    }

    private ClimateBiome resolveWater(double latitude, double longitude, double elevationMeters, WaterType water) {
        return switch (water) {
            case LAKE -> Math.abs(latitude) >= POLAR ? ClimateBiome.FROZEN_OCEAN : ClimateBiome.LAKE;
            case RIVER -> ClimateBiome.RIVER;
            case OCEAN -> {
                if (Math.abs(latitude) >= POLAR) {
                    yield ClimateBiome.FROZEN_OCEAN;
                }
                if (elevationMeters < -DEEP_OCEAN_DEPTH + wander(latitude, longitude, SHELF_WANDER, 0x53484C46L)) {
                    yield ClimateBiome.DEEP_OCEAN;
                }
                yield Math.abs(latitude) < TROPICS + wander(latitude, longitude, TROPICS_WANDER, 0x54524F50L)
                        ? ClimateBiome.WARM_OCEAN : ClimateBiome.OCEAN;
            }
            case NONE -> throw new IllegalArgumentException("not water");
        };
    }

    private ClimateBiome forestFor(double absLatitude) {
        if (absLatitude >= POLAR) {
            return ClimateBiome.TUNDRA;
        }
        // Boreal forest starts around the 55th parallel -- southern Sweden, not northern Germany.
        if (absLatitude >= 55.0) {
            return ClimateBiome.BOREAL_FOREST;
        }
        if (absLatitude >= TROPICS) {
            return ClimateBiome.TEMPERATE_FOREST;
        }
        return absLatitude < 10.0
                ? ClimateBiome.TROPICAL_RAINFOREST
                : ClimateBiome.TROPICAL_SEASONAL_FOREST;
    }

    private ClimateBiome grasslandFor(double absLatitude, boolean mountainous) {
        if (mountainous) {
            return ClimateBiome.MOUNTAIN_MEADOW;
        }
        if (absLatitude >= POLAR) {
            return ClimateBiome.TUNDRA;
        }
        return absLatitude < TROPICS ? ClimateBiome.SAVANNA : ClimateBiome.GRASSLAND;
    }
}
