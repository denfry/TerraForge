package dev.terraforge.core.geodesy;

import dev.terraforge.core.coord.GeoPoint;

/**
 * Real-world distances and bearings on the WGS84 ellipsoid.
 *
 * <p>Minecraft block distance is meaningless as a geographic measure once a scale is applied, so
 * every user-facing distance ({@code /earth distance}, {@code TownGeoService}) goes through here.
 *
 * <p>{@link #haversineMeters} is the cheap spherical approximation (error up to ~0.5%);
 * {@link #vincentyMeters} is the accurate ellipsoidal solution used for reported distances.
 */
public final class Geodesy {

    /** WGS84 semi-major axis, metres. */
    public static final double WGS84_A = 6_378_137.0;
    /** WGS84 flattening. */
    public static final double WGS84_F = 1.0 / 298.257_223_563;
    /** WGS84 semi-minor axis, metres. */
    public static final double WGS84_B = WGS84_A * (1.0 - WGS84_F);
    /** Mean Earth radius used by the haversine formula, metres. */
    public static final double MEAN_RADIUS = 6_371_008.8;

    private static final int VINCENTY_MAX_ITERATIONS = 200;
    private static final double VINCENTY_TOLERANCE = 1.0e-12;

    private Geodesy() {
    }

    /** Great-circle distance in metres (spherical Earth). */
    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double sinLat = Math.sin(dLat / 2.0);
        double sinLon = Math.sin(dLon / 2.0);
        double a = sinLat * sinLat
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinLon * sinLon;
        return 2.0 * MEAN_RADIUS * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public static double haversineMeters(GeoPoint a, GeoPoint b) {
        return haversineMeters(a.latitude(), a.longitude(), b.latitude(), b.longitude());
    }

    /**
     * Vincenty inverse solution -- geodesic distance in metres on the WGS84 ellipsoid.
     *
     * <p>Falls back to {@link #haversineMeters} for near-antipodal points, where the iteration is
     * known not to converge.
     */
    public static double vincentyMeters(double lat1, double lon1, double lat2, double lon2) {
        double L = Math.toRadians(lon2 - lon1);
        double U1 = Math.atan((1.0 - WGS84_F) * Math.tan(Math.toRadians(lat1)));
        double U2 = Math.atan((1.0 - WGS84_F) * Math.tan(Math.toRadians(lat2)));
        double sinU1 = Math.sin(U1), cosU1 = Math.cos(U1);
        double sinU2 = Math.sin(U2), cosU2 = Math.cos(U2);

        double lambda = L;
        double sinSigma = 0, cosSigma = 0, sigma = 0, cos2SigmaM = 0, cosSqAlpha = 0;

        for (int i = 0; i < VINCENTY_MAX_ITERATIONS; i++) {
            double sinLambda = Math.sin(lambda), cosLambda = Math.cos(lambda);
            sinSigma = Math.sqrt(Math.pow(cosU2 * sinLambda, 2)
                    + Math.pow(cosU1 * sinU2 - sinU1 * cosU2 * cosLambda, 2));
            if (sinSigma == 0.0) {
                return 0.0; // coincident points
            }
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda;
            sigma = Math.atan2(sinSigma, cosSigma);
            double sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma;
            cosSqAlpha = 1.0 - sinAlpha * sinAlpha;
            cos2SigmaM = cosSqAlpha == 0.0 ? 0.0 : cosSigma - 2.0 * sinU1 * sinU2 / cosSqAlpha;
            double C = WGS84_F / 16.0 * cosSqAlpha * (4.0 + WGS84_F * (4.0 - 3.0 * cosSqAlpha));
            double lambdaPrev = lambda;
            lambda = L + (1.0 - C) * WGS84_F * sinAlpha
                    * (sigma + C * sinSigma * (cos2SigmaM + C * cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM)));
            if (Math.abs(lambda - lambdaPrev) < VINCENTY_TOLERANCE) {
                double uSq = cosSqAlpha * (WGS84_A * WGS84_A - WGS84_B * WGS84_B) / (WGS84_B * WGS84_B);
                double A = 1.0 + uSq / 16384.0 * (4096.0 + uSq * (-768.0 + uSq * (320.0 - 175.0 * uSq)));
                double B = uSq / 1024.0 * (256.0 + uSq * (-128.0 + uSq * (74.0 - 47.0 * uSq)));
                double deltaSigma = B * sinSigma * (cos2SigmaM + B / 4.0
                        * (cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM)
                        - B / 6.0 * cos2SigmaM * (-3.0 + 4.0 * sinSigma * sinSigma)
                        * (-3.0 + 4.0 * cos2SigmaM * cos2SigmaM)));
                return WGS84_B * A * (sigma - deltaSigma);
            }
        }
        // Near-antipodal: Vincenty does not converge, degrade gracefully instead of failing.
        return haversineMeters(lat1, lon1, lat2, lon2);
    }

    public static double vincentyMeters(GeoPoint a, GeoPoint b) {
        return vincentyMeters(a.latitude(), a.longitude(), b.latitude(), b.longitude());
    }

    /** Initial bearing in degrees from north, range [0, 360). */
    public static double initialBearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    /** Compass label ("NE", "SSW", ...) for a bearing, for user-facing messages. */
    public static String compassPoint(double bearingDegrees) {
        String[] points = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        int index = (int) Math.round(((bearingDegrees % 360.0) + 360.0) % 360.0 / 22.5) % 16;
        return points[index];
    }
}
