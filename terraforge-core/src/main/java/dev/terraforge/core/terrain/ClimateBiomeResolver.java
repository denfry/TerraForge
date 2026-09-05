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

    /**
     * Elevation above which trees stop, in metres: about 4000 m at the equator, 2000 m in the Alps,
     * sea level near the poles. A linear fit is enough at the resolution TerraForge generates at.
     */
    public static double treelineMeters(double latitude) {
        return Math.max(0.0, 4000.0 - 55.0 * Math.abs(latitude));
    }

    /** Elevation above which snow is permanent, in metres. Runs roughly 900 m above the treeline. */
    public static double snowlineMeters(double latitude) {
        return Math.max(MIN_SNOWLINE, 4900.0 - 62.0 * Math.abs(latitude));
    }

    /**
     * @param latitude       degrees; sign is ignored, climate is symmetric about the equator
     * @param elevationMeters real elevation above sea level
     * @param water          water classification of the column
     * @param landcover      land cover class, {@link LandcoverClass#UNKNOWN} when unavailable
     */
    public ClimateBiome resolve(double latitude, double elevationMeters,
                                WaterType water, LandcoverClass landcover) {
        if (water.isWater()) {
            return resolveWater(latitude, elevationMeters, water);
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
        if (elevationMeters > snowlineMeters(latitude)) {
            return ClimateBiome.GLACIER;
        }
        // Beyond the polar circles there are no trees at any elevation, so the treeline says
        // nothing there: the land is tundra until it is permanent ice.
        if (absLatitude >= POLAR) {
            return landcover == LandcoverClass.BARE_SPARSE ? ClimateBiome.BARE_ROCK : ClimateBiome.TUNDRA;
        }
        double treeline = treelineMeters(latitude);
        if (elevationMeters > treeline) {
            // Above the treeline the surface is alpine mat, then bare rock further up.
            return elevationMeters > treeline + ALPINE_BAND ? ClimateBiome.BARE_ROCK : ClimateBiome.ALPINE;
        }
        // Mountain varieties need both a real altitude and proximity to the treeline, so a plain at
        // 60 degrees north does not become a mountain just because its treeline is low.
        boolean mountainous = elevationMeters > MOUNTAIN_ELEVATION && elevationMeters > treeline - 700.0;

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

    private ClimateBiome resolveWater(double latitude, double elevationMeters, WaterType water) {
        return switch (water) {
            case LAKE -> Math.abs(latitude) >= POLAR ? ClimateBiome.FROZEN_OCEAN : ClimateBiome.LAKE;
            case RIVER -> ClimateBiome.RIVER;
            case OCEAN -> {
                if (Math.abs(latitude) >= POLAR) {
                    yield ClimateBiome.FROZEN_OCEAN;
                }
                if (elevationMeters < -DEEP_OCEAN_DEPTH) {
                    yield ClimateBiome.DEEP_OCEAN;
                }
                yield Math.abs(latitude) < TROPICS ? ClimateBiome.WARM_OCEAN : ClimateBiome.OCEAN;
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
