package dev.terraforge.core.api;

import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.core.terrain.VerticalScale;

/**
 * Public API for terrain queries: the same pipeline the chunk generator uses, exposed read-only.
 *
 * <p>Deterministic by contract -- the same geographic point always yields the same sample, for the
 * same data set and configuration.
 */
public interface TerrainService {

    VerticalScale verticalScale();

    /** Resolved terrain data for a geographic point. */
    TerrainSample sample(double latitude, double longitude);

    /** Elevation in metres, or the configured fallback when no DEM tile covers the point. */
    double elevationAt(double latitude, double longitude);

    /** True when the point lies in ocean, lake or river. */
    boolean isWater(double latitude, double longitude);
}
