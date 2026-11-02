package dev.terraforge.generator.pipeline;

import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.generator.noise.CellNoise;

/**
 * Adds the relief a DEM averaged over a kilometre cannot carry.
 *
 * <p>At a kilometre per block every column is the mean of a square kilometre, and the smoothing
 * window averages several of those again. What is left is the shape of the land with none of its
 * texture: hills are even domes, mountain sides are planes, valleys are V-shaped troughs, and from
 * above every slope is a set of evenly spaced contour steps. Real ground at this size has spurs,
 * gullies, knolls and crags.
 *
 * <p>This pass puts some back: deterministic noise, seeded by the block position and nothing else,
 * whose amplitude follows the local slope. A plain moves by a block or so, a mountain side by several
 * blocks, so the detail sits where the relief already is and never raises a mountain on a plain. It
 * moves land surfaces only; water, its beds and its surface are untouched, and {@link ShoreShaper}
 * runs afterwards, so banks still meet the water as before.
 *
 * <p>The slope is read from the four neighbours of a column, so the pass needs one column of margin
 * beyond any column whose result is used. The sampler provides it; every column is then shaped from
 * the same neighbours whichever chunk asks for it, and chunk borders do not show.
 */
final class ReliefDetail {

    private static final long SALT = 0x52454C49454656L;
    /** Blocks of displacement per block of slope. */
    private static final double SLOPE_GAIN = 1.0;
    /** Displacement on flat land: a gentle roll instead of a perfectly level plain. */
    private static final double FLAT_AMPLITUDE = 1.5;
    /** No column moves further than this, in blocks, at {@code detail} 1. */
    private static final double MAX_AMPLITUDE = 10.0;
    private static final double RIDGE_MEAN = 0.357;

    private ReliefDetail() {
    }

    /**
     * Shapes the land columns of a square grid in place.
     *
     * @param grid   row-major samples, {@code width} on a side
     * @param width  the grid's edge in columns
     * @param originX block X of the grid's first column
     * @param originZ block Z of the grid's first row
     * @param detail  strength; {@code 0} does nothing, {@code 1} is calibrated
     * @param minY    lowest surface Y allowed
     * @param maxY    highest surface Y allowed
     */
    static void apply(TerrainSample[] grid, int width, int originX, int originZ, double detail, int minY, int maxY) {
        if (!(detail > 0.0)) {
            return;
        }
        TerrainSample[] source = grid.clone();
        for (int z = 1; z < width - 1; z++) {
            for (int x = 1; x < width - 1; x++) {
                TerrainSample sample = source[z * width + x];
                if (sample.isWater()) {
                    continue;
                }
                int y = sample.surfaceY();
                double slopeX = (source[z * width + x + 1].surfaceY() - source[z * width + x - 1].surfaceY()) * 0.5;
                double slopeZ = (source[(z + 1) * width + x].surfaceY() - source[(z - 1) * width + x].surfaceY()) * 0.5;
                double slope = Math.sqrt(slopeX * slopeX + slopeZ * slopeZ);
                double amplitude = Math.min(MAX_AMPLITUDE, FLAT_AMPLITUDE + SLOPE_GAIN * slope) * detail;
                int shifted = (int) Math.round(y + amplitude * noise(originX + x, originZ + z));
                shifted = Math.max(minY, Math.min(maxY, shifted));
                if (shifted != y) {
                    grid[z * width + x] = new TerrainSample(sample.elevationMeters(), shifted, sample.waterType(),
                            sample.waterSurfaceY(), sample.landcover(), sample.biome(), sample.fromFallback());
                }
            }
        }
    }

    /**
     * Three octaves in {@code [-1, 1]}: knolls and spurs about a dozen blocks across, with gullies and
     * ledges inside them. The middle octave is ridged, so slopes break into crests rather than bumps.
     */
    static double noise(int x, int z) {
        double broad = CellNoise.smooth(x, z, 14, SALT) * 2.0 - 1.0;
        // Centred: |2s - 1| averages about 0.36 over the smooth noise, so the ridges neither raise nor
        // lower the land on average.
        double ridge = (RIDGE_MEAN - Math.abs(CellNoise.smooth(x, z, 7, SALT + 1) * 2.0 - 1.0)) * 2.0;
        double fine = CellNoise.smooth(x, z, 3, SALT + 2) * 2.0 - 1.0;
        return Math.max(-1.0, Math.min(1.0, 0.55 * broad + 0.3 * ridge + 0.15 * fine));
    }
}
