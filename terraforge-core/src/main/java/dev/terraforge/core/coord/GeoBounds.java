package dev.terraforge.core.coord;

/**
 * An axis-aligned geographic bounding box in WGS84 degrees.
 *
 * <p>Antimeridian-crossing boxes are not supported; split them into two boxes instead. This keeps
 * the spatial index and the DEM tile lookup free of wrap-around special cases.
 */
public record GeoBounds(double minLatitude, double minLongitude, double maxLatitude, double maxLongitude) {

    public GeoBounds {
        if (minLatitude > maxLatitude) {
            throw new IllegalArgumentException("minLatitude > maxLatitude");
        }
        if (minLongitude > maxLongitude) {
            throw new IllegalArgumentException(
                    "minLongitude > maxLongitude (antimeridian-crossing bounds must be split)");
        }
    }

    /** The whole planet. */
    public static GeoBounds world() {
        return new GeoBounds(-90.0, -180.0, 90.0, 180.0);
    }

    public static GeoBounds of(GeoPoint a, GeoPoint b) {
        return new GeoBounds(
                Math.min(a.latitude(), b.latitude()),
                Math.min(a.longitude(), b.longitude()),
                Math.max(a.latitude(), b.latitude()),
                Math.max(a.longitude(), b.longitude()));
    }

    public boolean contains(double latitude, double longitude) {
        return latitude >= minLatitude && latitude <= maxLatitude
                && longitude >= minLongitude && longitude <= maxLongitude;
    }

    public boolean contains(GeoPoint point) {
        return contains(point.latitude(), point.longitude());
    }

    public boolean intersects(GeoBounds other) {
        return minLatitude <= other.maxLatitude && maxLatitude >= other.minLatitude
                && minLongitude <= other.maxLongitude && maxLongitude >= other.minLongitude;
    }

    public GeoBounds expand(double degrees) {
        return new GeoBounds(
                GeoPoint.clampLatitude(minLatitude - degrees),
                minLongitude - degrees,
                GeoPoint.clampLatitude(maxLatitude + degrees),
                maxLongitude + degrees);
    }

    public GeoPoint center() {
        return new GeoPoint((minLatitude + maxLatitude) / 2.0, (minLongitude + maxLongitude) / 2.0);
    }

    public double latitudeSpan() {
        return maxLatitude - minLatitude;
    }

    public double longitudeSpan() {
        return maxLongitude - minLongitude;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "[%.4f,%.4f -> %.4f,%.4f]", minLatitude, minLongitude, maxLatitude, maxLongitude);
    }
}
