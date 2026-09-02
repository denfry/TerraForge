package dev.terraforge.core.data;

/**
 * Water classification derived from elevation alone: anything below sea level is ocean.
 *
 * <p>The honest fallback until vector coastlines and water polygons arrive (Phase 6). It gets
 * oceans and the shape of every coastline right, because a land DEM stops at the coast, and it
 * gets inland water wrong -- the Caspian Sea and the Dutch polders both sit below sea level and
 * are not ocean. Lakes and rivers are therefore reported as {@link WaterType#NONE} rather than
 * guessed at.
 *
 * <p>Immutable and thread-safe.
 */
public final class SeaLevelWaterProvider implements WaterProvider {

    private final ElevationProvider elevation;

    public SeaLevelWaterProvider(ElevationProvider elevation) {
        this.elevation = elevation;
    }

    @Override
    public WaterType waterTypeAt(double latitude, double longitude) {
        return waterTypeAt(latitude, longitude, elevation.elevationAt(latitude, longitude));
    }

    @Override
    public WaterType waterTypeAt(double latitude, double longitude, double meters) {
        // Missing data is not evidence of ocean: an unprepared tile must not flood the world.
        if (ElevationProvider.isNoData(meters)) {
            return WaterType.NONE;
        }
        return meters < 0.0 ? WaterType.OCEAN : WaterType.NONE;
    }

    @Override
    public WaterColumn waterColumnAt(double latitude, double longitude, double knownElevationMeters) {
        // The only water this provider can honestly report is the ocean, and its surface is sea
        // level by definition. There is no bed depth: the DEM's own negative values are the floor.
        return waterTypeAt(latitude, longitude, knownElevationMeters) == WaterType.OCEAN
                ? WaterColumn.OCEAN
                : WaterColumn.DRY;
    }
}
