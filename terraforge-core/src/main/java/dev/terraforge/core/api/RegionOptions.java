package dev.terraforge.core.api;

/**
 * Tuning knobs for turning a country boundary into a manageable polygon.
 *
 * @param simplifyToleranceBlocks Douglas-Peucker tolerance, in Minecraft blocks: higher = fewer points
 * @param maxPointsPerRing        hard cap on points in one ring; the simplifier is re-run with a
 *                                coarser tolerance until the ring fits under the cap
 * @param minRingAreaBlocks       rings smaller than this projected area are dropped as slivers
 */
public record RegionOptions(double simplifyToleranceBlocks, int maxPointsPerRing, double minRingAreaBlocks) {

    public RegionOptions {
        if (simplifyToleranceBlocks <= 0.0 || !Double.isFinite(simplifyToleranceBlocks)) {
            throw new IllegalArgumentException(
                    "simplifyToleranceBlocks must be positive and finite: " + simplifyToleranceBlocks);
        }
        if (maxPointsPerRing < 4) {
            throw new IllegalArgumentException("maxPointsPerRing must be at least 4: " + maxPointsPerRing);
        }
        if (minRingAreaBlocks < 0.0 || !Double.isFinite(minRingAreaBlocks)) {
            throw new IllegalArgumentException(
                    "minRingAreaBlocks must be non-negative and finite: " + minRingAreaBlocks);
        }
    }

    /** Sensible defaults for a continent-sized union at 1 block/km. */
    public static RegionOptions defaults() {
        return new RegionOptions(25.0, 2_000, 64.0);
    }
}
