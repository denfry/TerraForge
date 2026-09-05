package dev.terraforge.core.terrain;

/**
 * The Antarctic ice sheet, as TerraForge draws it.
 *
 * <p>The continent is a 2 to 4 km-thick dome of ice with almost no relief on it, and no land cover
 * dataset describes it (ESA WorldCover stops at 60 degrees south). Read straight from the DEM at
 * a vertically exaggerated scale it becomes a kilometre-high cliff-edged plateau of packed ice,
 * which is neither what it looks like nor a place a player can cross. So south of {@link #LATITUDE}
 * the ice-sheet's elevation is compressed onto a gentle, low dome above sea level and the whole
 * land surface is treated as ice sheet: snow, with the odd patch of ice.
 *
 * <p>Pure functions; the rules live here so the biome resolver and the pipeline agree on them.
 */
public final class Antarctica {

    /**
     * Latitude, in degrees, south of which land is the Antarctic ice sheet. The continent's coast
     * lies south of 63 degrees everywhere except the tip of the Peninsula; 60 degrees is also
     * where WorldCover stops, so no real land cover is lost.
     */
    public static final double LATITUDE = -60.0;

    /** Elevation of the ice sheet at the coast, in metres above sea level. */
    public static final double COAST_METERS = 40.0;
    /** How much of the real ice-sheet relief survives; 3 % turns a 4 km dome into 120 m. */
    public static final double RELIEF = 0.03;
    /** The ice sheet never dips below this, so the coast always stands clear of the sea. */
    public static final double MIN_METERS = 20.0;

    private Antarctica() {
    }

    /** Whether a point lies on the Antarctic ice sheet. Water is never Antarctic; check it first. */
    public static boolean isIceSheet(double latitude) {
        return latitude <= LATITUDE;
    }

    /** The flattened ice-sheet elevation for a real one, in metres. */
    public static double flatten(double elevationMeters) {
        return Math.max(MIN_METERS, COAST_METERS + RELIEF * Math.max(0.0, elevationMeters));
    }
}
