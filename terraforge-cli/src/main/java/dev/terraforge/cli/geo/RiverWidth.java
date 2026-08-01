package dev.terraforge.cli.geo;

/**
 * Converts HydroRIVERS' long-term mean discharge to a visible channel width.
 *
 * <p>The deterministic hydraulic-geometry approximation is {@code 7.2 * sqrt(Q)} metres, where
 * {@code Q} is {@code DIS_AV_CMS} in m³/s.  It gives a 72 m channel at 100 m³/s and 720 m at
 * 10,000 m³/s.  The result is never narrower than one configured horizontal block: at the default
 * 1 block/km, retaining narrower rivers as a 1 km channel is preferable to silently erasing every
 * small river during downsampling.
 */
final class RiverWidth {

    private static final double WIDTH_FACTOR = 7.2;
    private static final double METRES_PER_KILOMETRE = 1_000.0;

    private RiverWidth() {
    }

    static double metres(double dischargeCubicMetresPerSecond, double blocksPerKm) {
        if (!(blocksPerKm > 0.0) || !Double.isFinite(blocksPerKm)) {
            throw new IllegalArgumentException("blocks-per-km must be positive and finite");
        }
        double discharge = Double.isFinite(dischargeCubicMetresPerSecond)
                ? Math.max(0.0, dischargeCubicMetresPerSecond) : 0.0;
        return Math.max(METRES_PER_KILOMETRE / blocksPerKm, WIDTH_FACTOR * Math.sqrt(discharge));
    }

    /** A shallow alluvial channel: one twentieth of width, bounded to 1..10 metres. */
    static double bedDepthMetres(double widthMetres) {
        return Math.clamp(widthMetres / 20.0, 1.0, 10.0);
    }
}
