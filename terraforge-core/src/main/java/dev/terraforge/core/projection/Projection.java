package dev.terraforge.core.projection;

import dev.terraforge.core.coord.GeoPoint;

/**
 * Maps geographic coordinates onto a flat plane measured in <strong>metres</strong>.
 *
 * <p>Implementations must be pure, thread-safe and exactly invertible within floating point error:
 * chunk generation is deterministic only if {@code toGeographic(toPlane(p)) == p}.
 *
 * <p>Note the separation of concerns: a projection knows nothing about blocks, scale or the world
 * origin. Turning plane metres into Minecraft block coordinates is
 * {@link dev.terraforge.core.coord.CoordinateTransformer}'s job.
 */
public interface Projection {

    /** Mean Earth radius used by the spherical projections (metres, WGS84 semi-major axis). */
    double EARTH_RADIUS_METERS = 6_378_137.0;

    /** Stable identifier used in configuration and persisted world metadata. */
    String id();

    /** Human readable description including the documented distortion behaviour. */
    String description();

    /**
     * Projects a geographic point onto the plane.
     *
     * @return easting/northing in metres, relative to the projection's own origin (lat 0, lon 0)
     */
    PlanePoint toPlane(double latitude, double longitude);

    default PlanePoint toPlane(GeoPoint point) {
        return toPlane(point.latitude(), point.longitude());
    }

    /** Inverse of {@link #toPlane(double, double)}. */
    GeoPoint toGeographic(double east, double north);

    default GeoPoint toGeographic(PlanePoint point) {
        return toGeographic(point.east(), point.north());
    }

    /**
     * Local linear distortion at a given latitude: one plane metre equals
     * {@code 1 / scaleFactor(latitude)} real ground metres.
     *
     * <p>Web Mercator returns {@code 1 / cos(lat)}, so terrain near the poles is stretched. The
     * terrain pipeline uses this to keep vertical exaggeration visually consistent with the
     * horizontal stretch.
     */
    double scaleFactor(double latitude);

    /** Latitude range the projection is defined for (Web Mercator cuts off near the poles). */
    double maxLatitude();

    default double minLatitude() {
        return -maxLatitude();
    }
}
