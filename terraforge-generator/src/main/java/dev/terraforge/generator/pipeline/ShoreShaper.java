package dev.terraforge.generator.pipeline;

import dev.terraforge.core.terrain.TerrainSample;

/**
 * Makes shores look like shores: banks that slope into the water, beds that shelve out from it.
 *
 * <p>A lake sits at its own altitude and the land around it at the DEM's. At a coarse vertical scale
 * the two disagree by several blocks, so the bank was a sheer wall straight into deep water, and
 * the bed -- carved to its floor everywhere -- a flat slab with a rim. Two rules fix both, each a
 * function of the distance to the nearest column of the other kind:
 *
 * <ul>
 *   <li>a land column within reach of water is never higher than that water's surface plus its
 *       distance from it, so the bank climbs at most one block per block;</li>
 *   <li>a water column within reach of land is never deeper than its distance from it, so the bed
 *       shelves out one block per block before reaching its full depth.</li>
 * </ul>
 *
 * <p>Both rules fade out across the reach instead of stopping at its edge. A hard limit left the
 * ground just beyond it untouched, so a coast below high land became a strip of beach and then a
 * wall -- as high as the land was above the water -- exactly {@code reach} blocks inland, and a
 * sea bed shelved out for {@code reach} blocks and then dropped sheer to its full depth. Now the
 * limit gives way quadratically with distance, reaching the untouched height one block past the
 * reach: gentle at the waterline, and no step anywhere the data does not have one.
 *
 * <p>One rule raises instead of lowering: land touching water is never below that water's surface.
 * The DEM puts coastal flats and polders a few metres under the sea, and the water data does not
 * always cover them, so a dry column could sit two blocks below the ocean beside it -- water held up
 * by nothing, which floods the moment a player breaks the block next to it. Raised to the water's
 * level, that column is the bank the water rests against.
 *
 * <p>Otherwise both rules only move the surface towards the water: a bank is lowered, a bed is raised. Nothing
 * else in the sample changes. The rules read a margin of columns around the chunk, which the
 * sampler provides, so the result is the same whichever chunk a column is looked at from.
 *
 * <p>Pure and allocation-light; the sampler runs it once per chunk.
 */
final class ShoreShaper {

    private ShoreShaper() {
    }

    /**
     * Shapes the shores on a square grid of samples in place.
     *
     * @param grid   row-major samples, {@code width} on a side
     * @param width  the grid's edge in columns
     * @param reach  how many columns a shore's influence extends; {@code 0} does nothing
     * @param margin columns of margin on each side that only inform the interior and are not written
     */
    static void shape(TerrainSample[] grid, int width, int reach, int margin) {
        if (reach <= 0) {
            return;
        }
        TerrainSample[] source = grid.clone();
        for (int z = margin; z < width - margin; z++) {
            for (int x = margin; x < width - margin; x++) {
                TerrainSample sample = source[z * width + x];
                TerrainSample shaped = sample.isWater()
                        ? shelve(source, width, x, z, reach, sample)
                        : bank(source, width, x, z, reach, sample);
                if (shaped != sample) {
                    grid[z * width + x] = shaped;
                }
            }
        }
    }

    /**
     * A land column: near the water no higher than the water's surface plus the distance to it, the
     * excess above that let back in as the distance grows.
     */
    private static TerrainSample bank(TerrainSample[] source, int width, int x, int z, int reach,
                                      TerrainSample sample) {
        int touching = ringMax(source, width, x, z, 1, true);
        if (touching != Integer.MIN_VALUE && sample.surfaceY() < touching) {
            return withSurface(sample, touching); // a bank, not a hole beside the water
        }
        for (int d = 1; d <= reach; d++) {
            int ring = ringMax(source, width, x, z, d, true);
            if (ring != Integer.MIN_VALUE) {
                int ceiling = ring + d;
                int excess = sample.surfaceY() - ceiling;
                return excess <= 0 ? sample : withSurface(sample, ceiling + fade(excess, d, reach));
            }
        }
        return sample;
    }

    /**
     * A water column: near the land no deeper than its distance to it, the depth beyond that let back
     * in as the distance grows.
     */
    private static TerrainSample shelve(TerrainSample[] source, int width, int x, int z, int reach,
                                        TerrainSample sample) {
        for (int d = 1; d <= reach; d++) {
            if (ringMax(source, width, x, z, d, false) != Integer.MIN_VALUE) {
                int floor = sample.waterSurfaceY() - d;
                int excess = floor - sample.surfaceY();
                return excess <= 0 ? sample : withSurface(sample, floor - fade(excess, d, reach));
            }
        }
        return sample;
    }

    /**
     * How much of {@code excess} a column {@code d} blocks from the shore keeps: none at the waterline,
     * all of it one block past the reach, quadratic in between.
     */
    static int fade(int excess, int d, int reach) {
        double t = (double) d / (reach + 1);
        return (int) Math.round(excess * t * t);
    }

    /**
     * The highest water surface (when {@code water}) or any land marker on the square ring at
     * Chebyshev distance {@code d}; {@link Integer#MIN_VALUE} when the ring holds none of that kind.
     */
    private static int ringMax(TerrainSample[] source, int width, int cx, int cz, int d, boolean water) {
        int best = Integer.MIN_VALUE;
        for (int dz = -d; dz <= d; dz++) {
            int z = cz + dz;
            if (z < 0 || z >= width) {
                continue;
            }
            boolean edgeRow = Math.abs(dz) == d;
            for (int dx = -d; dx <= d; dx += edgeRow ? 1 : 2 * d) {
                int x = cx + dx;
                if (x < 0 || x >= width) {
                    continue;
                }
                TerrainSample other = source[z * width + x];
                if (water) {
                    if (other.isWater()) {
                        best = Math.max(best, other.waterSurfaceY());
                    }
                } else if (!other.isWater()) {
                    best = 0;
                }
            }
        }
        return best;
    }

    private static TerrainSample withSurface(TerrainSample sample, int surfaceY) {
        return new TerrainSample(sample.elevationMeters(), surfaceY, sample.waterType(),
                sample.waterSurfaceY(), sample.landcover(), sample.biome(), sample.fromFallback());
    }
}
