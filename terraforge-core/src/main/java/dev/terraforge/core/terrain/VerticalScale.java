package dev.terraforge.core.terrain;

/**
 * Converts real elevation in metres to a Minecraft Y level and back.
 *
 * <pre>
 *   y = seaLevel + elevation * verticalExaggeration / metersPerBlock
 * </pre>
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

    public VerticalScale(int seaLevel, int minY, int maxY, double verticalExaggeration, double metersPerBlock) {
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

    /** Unclamped Y for an elevation, keeping sub-block precision for interpolation. */
    public double rawY(double elevationMeters) {
        return seaLevel + elevationMeters * verticalExaggeration / metersPerBlock;
    }

    /** Final block Y for an elevation, soft-clamped into the world's build limits. */
    public int toBlockY(double elevationMeters) {
        return (int) Math.round(softClamp(rawY(elevationMeters)));
    }

    /** Inverse of {@link #toBlockY}; used by {@code /earth whereami} to report elevation. */
    public double toElevationMeters(double y) {
        return (y - seaLevel) * metersPerBlock / verticalExaggeration;
    }

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
                + ", exaggeration=" + verticalExaggeration + ", metersPerBlock=" + metersPerBlock + '}';
    }
}
