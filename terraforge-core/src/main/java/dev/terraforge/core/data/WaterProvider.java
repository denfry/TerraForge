package dev.terraforge.core.data;

/**
 * Classifies a geographic point as land or as a specific kind of water body.
 *
 * <p>Kept separate from elevation because the two come from different datasets: elevation from a
 * DEM raster, water from vector coastlines and water polygons. A point below sea level is not
 * automatically ocean -- the Dead Sea shore and the Dutch polders are land.
 */
public interface WaterProvider {

    WaterType waterTypeAt(double latitude, double longitude);

    /**
     * Same question, with the column's elevation already known.
     *
     * <p>Exists so an implementation derived from elevation does not have to look it up a second
     * time -- the terrain pipeline has it in hand, and a DEM query per column is the single most
     * expensive step in generation. Implementations backed by their own data ignore the hint.
     */
    default WaterType waterTypeAt(double latitude, double longitude, double knownElevationMeters) {
        return waterTypeAt(latitude, longitude);
    }

    /**
     * Water surface elevation in metres, for lakes that do not sit at sea level (Lake Geneva at
     * 372 m). Returns 0 for ocean, and {@link ElevationProvider#NO_DATA} when unknown.
     */
    double waterSurfaceElevation(double latitude, double longitude);

    /** Water surface, with the column's elevation already known. See the hint above. */
    default double waterSurfaceElevation(double latitude, double longitude, double knownElevationMeters) {
        return waterSurfaceElevation(latitude, longitude);
    }

    /** Water classification of one column. */
    enum WaterType {
        NONE,
        OCEAN,
        LAKE,
        RIVER;

        public boolean isWater() {
            return this != NONE;
        }
    }
}
