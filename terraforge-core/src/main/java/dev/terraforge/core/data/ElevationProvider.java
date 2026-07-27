package dev.terraforge.core.data;

import dev.terraforge.core.coord.GeoBounds;

/**
 * Source of real-world elevation, in metres above sea level.
 *
 * <p>The generator talks to this interface and never to a raster format: the DEM implementation
 * lives in {@code terraforge-geo}, a flat-world or test implementation can be substituted, and the
 * core stays free of GIS code.
 *
 * <p>Implementations must be safe for concurrent reads -- several chunk worker threads query the
 * same provider at once.
 */
public interface ElevationProvider {

    /**
     * Returned when no data covers a point. Deliberately NaN rather than 0 or a magic negative
     * number: a missing sample can never be mistaken for sea level, and it poisons arithmetic
     * loudly instead of silently producing flat terrain.
     */
    double NO_DATA = Double.NaN;

    static boolean isNoData(double elevationMeters) {
        return Double.isNaN(elevationMeters);
    }

    /**
     * Elevation at a geographic point, or {@link #NO_DATA} when the point is not covered.
     *
     * <p>Callers apply their own fallback; the provider never invents a value.
     */
    double elevationAt(double latitude, double longitude);

    /** True when prepared data covers the point, without loading it. */
    boolean hasCoverage(double latitude, double longitude);

    /** Bounding box of all prepared data; the whole world when nothing is prepared. */
    GeoBounds coverage();

    /**
     * True when the data includes real ocean depth (bathymetry merged during preparation). When
     * false, the water stage uses {@code water.default-ocean-depth} instead of the sampled value.
     */
    boolean hasBathymetry();
}
