package dev.terraforge.core.projection;

import dev.terraforge.core.coord.GeoPoint;

/**
 * Spherical Web Mercator (EPSG:3857).
 *
 * <p><strong>Documented limitations.</strong> Mercator is conformal but not equal-area: horizontal
 * distances are stretched by {@code 1 / cos(latitude)}. At 50 degrees N (central Europe) that is a
 * factor of ~1.56, at 70 degrees N ~2.9. Latitudes beyond {@value #MAX_LATITUDE} degrees are
 * undefined and are clamped, so the poles cannot be generated with this projection. It is the
 * default because it keeps local shapes -- coastlines and mountain ranges look correct -- and it
 * matches the tiling scheme of virtually every raster dataset, which makes DEM lookup cheap.
 *
 * <p>For a world where north-south travel distance must match reality, configure
 * {@link EquirectangularProjection} instead.
 */
public final class WebMercatorProjection implements Projection {

    /** Standard Web Mercator cutoff, chosen so that the projected world is square. */
    public static final double MAX_LATITUDE = 85.051_128_779_806_59;

    public static final WebMercatorProjection INSTANCE = new WebMercatorProjection();

    @Override
    public String id() {
        return "web_mercator";
    }

    @Override
    public String description() {
        return "Web Mercator (EPSG:3857) -- conformal, area distortion 1/cos(lat), poles clipped at "
                + String.format(java.util.Locale.ROOT, "%.2f", MAX_LATITUDE) + " degrees";
    }

    @Override
    public PlanePoint toPlane(double latitude, double longitude) {
        double lat = Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
        double east = EARTH_RADIUS_METERS * Math.toRadians(GeoPoint.normaliseLongitude(longitude));
        double north = EARTH_RADIUS_METERS * Math.log(Math.tan(Math.PI / 4.0 + Math.toRadians(lat) / 2.0));
        return new PlanePoint(east, north);
    }

    @Override
    public GeoPoint toGeographic(double east, double north) {
        double longitude = Math.toDegrees(east / EARTH_RADIUS_METERS);
        double latitude = Math.toDegrees(2.0 * Math.atan(Math.exp(north / EARTH_RADIUS_METERS)) - Math.PI / 2.0);
        return new GeoPoint(GeoPoint.clampLatitude(latitude), GeoPoint.normaliseLongitude(longitude));
    }

    @Override
    public double scaleFactor(double latitude) {
        double lat = Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
        return 1.0 / Math.cos(Math.toRadians(lat));
    }

    @Override
    public double maxLatitude() {
        return MAX_LATITUDE;
    }
}
