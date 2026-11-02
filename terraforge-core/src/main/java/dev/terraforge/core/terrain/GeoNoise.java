package dev.terraforge.core.terrain;

/**
 * Smooth, seedless value noise over latitude and longitude, in {@code [-1, 1]}.
 *
 * <p>Climate thresholds -- the snowline, the treeline, the edge of the tropics, the shelf break --
 * are single numbers, and a single number draws a contour: one altitude where every slope turns
 * white, one parallel where the sea changes colour from one ocean to the next. Real boundaries wander
 * with aspect, shelter and local weather. This noise lets each threshold wander by a bounded amount,
 * the same way on every server: it depends on the position alone, never on a seed or the clock.
 */
public final class GeoNoise {

    private GeoNoise() {
    }

    /**
     * Two octaves of smooth noise, the second at a third of the cell size and half the weight.
     *
     * @param cellDegrees size of the coarse cell in degrees
     * @param salt        separates independent uses at the same place
     */
    public static double at(double latitude, double longitude, double cellDegrees, long salt) {
        double coarse = smooth(latitude / cellDegrees, longitude / cellDegrees, salt);
        double fine = smooth(latitude * 3.0 / cellDegrees, longitude * 3.0 / cellDegrees, salt ^ 0x5DEECE66DL);
        return (coarse * 2.0 + fine) / 3.0;
    }

    private static double smooth(double u, double v, long salt) {
        long u0 = (long) Math.floor(u);
        long v0 = (long) Math.floor(v);
        double fu = fade(u - u0);
        double fv = fade(v - v0);
        double a = corner(u0, v0, salt);
        double b = corner(u0 + 1, v0, salt);
        double c = corner(u0, v0 + 1, salt);
        double d = corner(u0 + 1, v0 + 1, salt);
        double top = a + (b - a) * fu;
        double bottom = c + (d - c) * fu;
        return top + (bottom - top) * fv;
    }

    private static double fade(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    /** A lattice value in {@code [-1, 1]} from a 64-bit mix of the cell and the salt. */
    private static double corner(long u, long v, long salt) {
        long h = u * 0x9E3779B97F4A7C15L ^ v * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return ((h >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }
}
