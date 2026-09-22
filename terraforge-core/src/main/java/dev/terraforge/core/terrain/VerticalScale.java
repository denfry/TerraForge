package dev.terraforge.core.terrain;

/**
 * Converts real elevation in metres to a Minecraft Y level and back.
 *
 * <pre>
 *   y = seaLevel + elevation * verticalExaggeration / metersPerBlock                  (linear)
 *   y = seaLevel + sign(v) * R * ln(1 + |v| / R) / metersPerBlock, v = elevation * exaggeration
 * </pre>
 *
 * <p>The second form is the relief curve, on when {@code reliefCurveMeters} ({@code R}) is positive.
 * At a kilometre or so per block horizontally, any vertical scale that shows a 200 m hill also turns
 * a 3,000 m range into a wall of single-column spikes: real slopes are multiplied by the ratio of
 * horizontal to vertical metres per block. The curve keeps {@code metersPerBlock} exactly at sea
 * level, where people live and build, and gives each block progressively more metres with height --
 * {@code metersPerBlock * (1 + v / R)} at elevation {@code v} -- so lowlands keep their relief while
 * mountains get the gentler slopes and the headroom they need. It is symmetric below the sea, which
 * turns an abyss into a deep floor instead of a cliff down to the bedrock.
 *
 * <p>{@code metersPerBlock} is the vertical metre budget of one block (1.0 keeps the classic
 * "1 block = 1 metre" feel). Exaggeration is applied before clamping, and clamping is soft: values
 * beyond the world limits are compressed into the last few blocks instead of being flattened, so
 * Everest still looks taller than the Alps even when both exceed {@code max-y}.
 *
 * <p>Immutable and thread-safe.
 */
public final class VerticalScale {

    /** Blocks reserved at each end for the soft-clamp ramp. */
    private static final int SOFT_CLAMP_MARGIN = 8;

    private final int seaLevel;
    private final int minY;
    private final int maxY;
    private final double verticalExaggeration;
    private final double metersPerBlock;
    private final double reliefCurveMeters;

    /** A linear scale: every block is {@code metersPerBlock} at every altitude. */
    public VerticalScale(int seaLevel, int minY, int maxY, double verticalExaggeration, double metersPerBlock) {
        this(seaLevel, minY, maxY, verticalExaggeration, metersPerBlock, 0.0);
    }

    /**
     * @param reliefCurveMeters {@code terrain.relief-curve-meters}; {@code 0} keeps the scale linear
     */
    public VerticalScale(int seaLevel, int minY, int maxY, double verticalExaggeration, double metersPerBlock,
                         double reliefCurveMeters) {
        if (minY >= maxY) {
            throw new IllegalArgumentException("min-y must be below max-y");
        }
        if (seaLevel <= minY || seaLevel >= maxY) {
            throw new IllegalArgumentException("sea-level must lie strictly between min-y and max-y");
        }
        if (verticalExaggeration <= 0.0 || !Double.isFinite(verticalExaggeration)) {
            throw new IllegalArgumentException("vertical-exaggeration must be positive: " + verticalExaggeration);
        }
        if (metersPerBlock <= 0.0 || !Double.isFinite(metersPerBlock)) {
            throw new IllegalArgumentException("meters-per-block must be positive: " + metersPerBlock);
        }
        if (reliefCurveMeters < 0.0 || !Double.isFinite(reliefCurveMeters)) {
            throw new IllegalArgumentException("relief-curve-meters must not be negative: " + reliefCurveMeters);
        }
        this.reliefCurveMeters = reliefCurveMeters;
        this.seaLevel = seaLevel;
        this.minY = minY;
        this.maxY = maxY;
        this.verticalExaggeration = verticalExaggeration;
        this.metersPerBlock = metersPerBlock;
    }

    public int seaLevel() {
        return seaLevel;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public double verticalExaggeration() {
        return verticalExaggeration;
    }

    public double metersPerBlock() {
        return metersPerBlock;
    }

    /** {@code 0} when the scale is linear; otherwise the relief curve's knee in metres. */
    public double reliefCurveMeters() {
        return reliefCurveMeters;
    }

    /** Unclamped Y for an elevation, keeping sub-block precision for interpolation. */
    public double rawY(double elevationMeters) {
        double v = elevationMeters * verticalExaggeration;
        if (reliefCurveMeters > 0.0) {
            v = Math.signum(v) * reliefCurveMeters * Math.log1p(Math.abs(v) / reliefCurveMeters);
        }
        return seaLevel + v / metersPerBlock;
    }

    /** Final block Y for an elevation, soft-clamped into the world's build limits. */
    public int toBlockY(double elevationMeters) {
        return (int) Math.round(softClamp(rawY(elevationMeters)));
    }

    /** Inverse of {@link #toBlockY}; used by {@code /earth whereami} to report elevation. */
    public double toElevationMeters(double y) {
        double v = (y - seaLevel) * metersPerBlock;
        if (reliefCurveMeters > 0.0) {
            v = Math.signum(v) * reliefCurveMeters * Math.expm1(Math.abs(v) / reliefCurveMeters);
        }
        return v / verticalExaggeration;
    }

    /** Highest elevation in metres that keeps its true shape, above which terrain is compressed. */
    public double highestUncompressedElevation() {
        return toElevationMeters(maxY - SOFT_CLAMP_MARGIN);
    }

    /** Deepest elevation in metres that keeps its true shape. Negative below sea level. */
    public double deepestUncompressedElevation() {
        return toElevationMeters(minY + SOFT_CLAMP_MARGIN);
    }

    /**
     * How this setting treats the real Earth, in one line, for the banner and {@code info}.
     *
     * <p>Worth stating out loud because the failure is invisible: a configuration that flattens
     * every mountain above 250 m into seven blocks is perfectly valid, generates without a
     * complaint, and is only discovered by standing on the result.
     */
    public String earthFit() {
        double highest = highestUncompressedElevation();
        double deepest = deepestUncompressedElevation();
        String range = String.format(java.util.Locale.ROOT,
                "true shape from %,.0f m to %,.0f m", deepest, highest);
        if (highest >= EVEREST_METRES && deepest <= MARIANA_METRES) {
            return range + " -- all of Earth fits";
        }
        StringBuilder clipped = new StringBuilder();
        if (highest < EVEREST_METRES) {
            clipped.append(String.format(java.util.Locale.ROOT,
                    "land above %,.0f m is compressed", highest));
        }
        if (deepest > MARIANA_METRES) {
            clipped.append(clipped.isEmpty() ? "" : ", ")
                    .append(String.format(java.util.Locale.ROOT,
                            "water below %,.0f m is compressed", -deepest));
        }
        return range + " -- " + clipped;
    }

    /** True when real terrain of any consequence is being squashed into the clamp margin. */
    public boolean flattensRealTerrain() {
        // A world that cannot show a thousand metres of relief cannot show a mountain range.
        return highestUncompressedElevation() < SIGNIFICANT_RELIEF_METRES;
    }

    /** Everest, and the deepest point of the Mariana Trench: the limits any Earth must reach. */
    private static final double EVEREST_METRES = 8849.0;
    private static final double MARIANA_METRES = -10935.0;

    /** Below this much representable relief, whole mountain ranges become one plateau. */
    private static final double SIGNIFICANT_RELIEF_METRES = 1000.0;

    /**
     * Compresses out-of-range values into the margin instead of hard-clipping, so relative height
     * differences survive at the extremes.
     */
    private double softClamp(double y) {
        double upperKnee = maxY - SOFT_CLAMP_MARGIN;
        double lowerKnee = minY + SOFT_CLAMP_MARGIN;
        // Asymptote one block inside the limits: after rounding, the result is always a buildable
        // block, never the world ceiling or the bedrock floor itself.
        double headroom = SOFT_CLAMP_MARGIN - 1.0;
        if (y > upperKnee) {
            double overshoot = y - upperKnee;
            return upperKnee + headroom * (overshoot / (overshoot + headroom));
        }
        if (y < lowerKnee) {
            double undershoot = lowerKnee - y;
            return lowerKnee - headroom * (undershoot / (undershoot + headroom));
        }
        return y;
    }

    @Override
    public String toString() {
        return "VerticalScale{seaLevel=" + seaLevel + ", minY=" + minY + ", maxY=" + maxY
                + ", exaggeration=" + verticalExaggeration + ", metersPerBlock=" + metersPerBlock
                + ", reliefCurveMeters=" + reliefCurveMeters + '}';
    }
}
