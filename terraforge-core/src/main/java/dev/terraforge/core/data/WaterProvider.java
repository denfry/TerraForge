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
     * Everything the terrain stage needs to know about water at one column, resolved in a single
     * lookup.
     *
     * <p>One call, not three. Classification, surface elevation and bed depth used to be separate
     * queries, which meant three spatial-index lookups and three geometry {@code covers} tests per
     * column -- and, worse, three answers that could disagree: a bounded geometry cache evicting
     * between the calls let a column be classified as a lake and then told it had no lake surface.
     * A column's water is one fact, so it is one query.
     *
     * @param knownElevationMeters the column's elevation, or {@link ElevationProvider#NO_DATA} when
     *                             the DEM does not cover it. A provider derived from elevation uses
     *                             it instead of looking the DEM up again; a provider backed by its
     *                             own vector data uses it only where the source has no absolute
     *                             surface elevation of its own (river centrelines).
     */
    WaterColumn waterColumnAt(double latitude, double longitude, double knownElevationMeters);

    /**
     * The water at one column.
     *
     * @param type                   classification; {@link WaterType#NONE} means dry land
     * @param surfaceElevationMeters water surface above sea level -- 0 for ocean, the lake's own
     *                               altitude for a lake (Lake Geneva at 372 m), the terrain height
     *                               for a river. {@link ElevationProvider#NO_DATA} when the source
     *                               does not know it, which callers must read as "cannot place this
     *                               water", never as "sea level": guessing sea level for a Tibetan
     *                               lake is a five-kilometre error.
     * @param bedDepthMeters         how far below the surface the bed sits, for water bodies whose
     *                               source ships a depth rather than bathymetry (rivers, lakes).
     *                               0 for ocean, whose floor comes from the DEM's bathymetry or the
     *                               configured default depth.
     */
    record WaterColumn(WaterType type, double surfaceElevationMeters, double bedDepthMeters) {

        /** No water here. */
        public static final WaterColumn DRY =
                new WaterColumn(WaterType.NONE, ElevationProvider.NO_DATA, 0.0);

        /** The ocean, whose surface is sea level by definition. */
        public static final WaterColumn OCEAN = new WaterColumn(WaterType.OCEAN, 0.0, 0.0);

        public boolean isWater() {
            return type.isWater();
        }

        /** True when this is water that can actually be placed: it has a known surface elevation. */
        public boolean hasKnownSurface() {
            return type.isWater() && !ElevationProvider.isNoData(surfaceElevationMeters);
        }
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
