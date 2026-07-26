package dev.terraforge.core.coord;

/**
 * An immutable geographic coordinate in WGS84 degrees.
 *
 * <p>This is the canonical currency of the whole GIS layer: providers, the spatial index and the
 * projection all speak {@code GeoPoint}, never Minecraft coordinates.
 *
 * @param latitude  degrees north, clamped to [-90, 90]
 * @param longitude degrees east, normalised to [-180, 180)
 */
public record GeoPoint(double latitude, double longitude) {

    public static final double MIN_LATITUDE = -90.0;
    public static final double MAX_LATITUDE = 90.0;

    public GeoPoint {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            throw new IllegalArgumentException("GeoPoint coordinates must not be NaN");
        }
        if (latitude < MIN_LATITUDE || latitude > MAX_LATITUDE) {
            throw new IllegalArgumentException("latitude out of range: " + latitude);
        }
        longitude = normaliseLongitude(longitude);
    }

    public static GeoPoint of(double latitude, double longitude) {
        return new GeoPoint(latitude, longitude);
    }

    /** Wraps an arbitrary longitude into the half-open range [-180, 180). */
    public static double normaliseLongitude(double longitude) {
        double wrapped = (longitude + 180.0) % 360.0;
        if (wrapped < 0.0) {
            wrapped += 360.0;
        }
        return wrapped - 180.0;
    }

    /** Clamps an arbitrary latitude into [-90, 90]. */
    public static double clampLatitude(double latitude) {
        return Math.max(MIN_LATITUDE, Math.min(MAX_LATITUDE, latitude));
    }

    public double latitudeRadians() {
        return Math.toRadians(latitude);
    }

    public double longitudeRadians() {
        return Math.toRadians(longitude);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "%.6f, %.6f", latitude, longitude);
    }
}
