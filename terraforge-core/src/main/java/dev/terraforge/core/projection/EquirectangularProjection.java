package dev.terraforge.core.projection;

import dev.terraforge.core.coord.GeoPoint;

/**
 * Equidistant cylindrical projection (EPSG:4087 family) with a configurable standard parallel.
 *
 * <p><strong>Documented limitations.</strong> North-south distances are exact everywhere; east-west
 * distances are exact only along the standard parallel and are distorted by
 * {@code cos(standardParallel) / cos(latitude)} elsewhere. Shapes are therefore not preserved:
 * far from the standard parallel, countries look horizontally squashed or stretched. Unlike Web
 * Mercator it covers the poles.
 *
 * <p>Recommended for regional worlds: set the standard parallel to the centre of the region (51.0
 * for the central-europe test region) and both distance and shape stay close to reality.
 */
public final class EquirectangularProjection implements Projection {

    private final double standardParallel;
    private final double cosStandardParallel;

    public EquirectangularProjection(double standardParallel) {
        if (standardParallel < -89.0 || standardParallel > 89.0) {
            throw new IllegalArgumentException("standardParallel out of range: " + standardParallel);
        }
        this.standardParallel = standardParallel;
        this.cosStandardParallel = Math.cos(Math.toRadians(standardParallel));
    }

    /** Plate carree: standard parallel at the equator. */
    public static EquirectangularProjection plateCarree() {
        return new EquirectangularProjection(0.0);
    }

    public double standardParallel() {
        return standardParallel;
    }

    @Override
    public String id() {
        return "equirectangular";
    }

    @Override
    public String description() {
        return "Equirectangular -- exact north-south distance, standard parallel "
                + String.format(java.util.Locale.ROOT, "%.2f", standardParallel) + " degrees, shape distortion away from it";
    }

    @Override
    public PlanePoint toPlane(double latitude, double longitude) {
        double east = EARTH_RADIUS_METERS
                * Math.toRadians(GeoPoint.normaliseLongitude(longitude)) * cosStandardParallel;
        double north = EARTH_RADIUS_METERS * Math.toRadians(GeoPoint.clampLatitude(latitude));
        return new PlanePoint(east, north);
    }

    @Override
    public GeoPoint toGeographic(double east, double north) {
        double longitude = Math.toDegrees(east / (EARTH_RADIUS_METERS * cosStandardParallel));
        double latitude = Math.toDegrees(north / EARTH_RADIUS_METERS);
        return new GeoPoint(GeoPoint.clampLatitude(latitude), GeoPoint.normaliseLongitude(longitude));
    }

    @Override
    public double scaleFactor(double latitude) {
        return cosStandardParallel / Math.cos(Math.toRadians(GeoPoint.clampLatitude(latitude)));
    }

    @Override
    public double maxLatitude() {
        return 90.0;
    }
}
