package dev.terraforge.generator.pipeline;

import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.terrain.TerrainSample;

/**
 * Resolves the 16x16 grid of {@link TerrainSample}s for one chunk.
 *
 * <p>This is the boundary between GIS and Minecraft: everything above it works in blocks, everything
 * below it in degrees and metres. Sampling is done once per chunk and cached, because the DEM,
 * water and landcover lookups dominate generation cost.
 *
 * <p>Implementations must be deterministic and thread-safe: Paper calls the generator from several
 * worker threads and the same chunk must always produce the same result.
 */
public interface ChunkSampler {

    /** 16x16 samples in row-major order, index {@code z * 16 + x}. */
    ChunkSamples sample(int chunkX, int chunkZ);

    /**
     * @param chunkX        chunk X
     * @param chunkZ        chunk Z
     * @param bounds        geographic bounds of the chunk
     * @param samples       256 samples, row-major {@code z * 16 + x}
     * @param neighbourhood surface and water of the columns around the chunk, or {@code null} when the
     *                      sampler computed none
     */
    record ChunkSamples(int chunkX, int chunkZ, GeoBounds bounds, TerrainSample[] samples,
                        Neighbourhood neighbourhood) {

        public static final int SIZE = 16;

        public ChunkSamples {
            if (samples.length != SIZE * SIZE) {
                throw new IllegalArgumentException("expected " + (SIZE * SIZE) + " samples, got " + samples.length);
            }
        }

        /** Samples with no knowledge of the columns around the chunk. */
        public ChunkSamples(int chunkX, int chunkZ, GeoBounds bounds, TerrainSample[] samples) {
            this(chunkX, chunkZ, bounds, samples, null);
        }

        /**
         * Surface Y of any column within {@link #reach()} of this chunk, in local coordinates
         * ({@code -reach .. 15 + reach}).
         */
        public int surfaceY(int localX, int localZ) {
            if (inside(localX, localZ)) {
                return at(localX, localZ).surfaceY();
            }
            return neighbourhood.surfaceY(localX, localZ);
        }

        /** Whether any column within {@link #reach()} of this chunk is water, in local coordinates. */
        public boolean isWater(int localX, int localZ) {
            if (inside(localX, localZ)) {
                return at(localX, localZ).isWater();
            }
            return neighbourhood.isWater(localX, localZ);
        }

        /** How many columns beyond the chunk edge {@link #surfaceY} and {@link #isWater} can see. */
        public int reach() {
            return neighbourhood == null ? 0 : neighbourhood.margin();
        }

        private static boolean inside(int localX, int localZ) {
            return localX >= 0 && localX < SIZE && localZ >= 0 && localZ < SIZE;
        }

        public TerrainSample at(int localX, int localZ) {
            return samples[localZ * SIZE + localX];
        }

        /** True when at least one column fell back to substituted data. */
        public boolean usedFallback() {
            for (TerrainSample sample : samples) {
                if (sample.fromFallback()) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Surface Y and water of the ring of columns a sampler computed around a chunk anyway -- the shore
     * margin -- kept as two flat arrays rather than as samples, so a cached chunk carries a few
     * kilobytes of context instead of a few hundred more sample objects.
     *
     * <p>Lets a pass that must not dig under water just over the chunk edge see that water without
     * sampling the neighbouring chunk.
     *
     * @param margin   columns beyond each chunk edge
     * @param surfaceY row-major over {@code (16 + 2 * margin)^2} columns, origin at {@code -margin}
     * @param water    same layout as {@code surfaceY}
     */
    record Neighbourhood(int margin, int[] surfaceY, boolean[] water) {

        public Neighbourhood {
            int width = ChunkSamples.SIZE + 2 * margin;
            if (margin < 0 || surfaceY.length != width * width || water.length != width * width) {
                throw new IllegalArgumentException("neighbourhood arrays do not match margin " + margin);
            }
        }

        int surfaceY(int localX, int localZ) {
            return surfaceY[index(localX, localZ)];
        }

        boolean isWater(int localX, int localZ) {
            return water[index(localX, localZ)];
        }

        private int index(int localX, int localZ) {
            int width = ChunkSamples.SIZE + 2 * margin;
            int gx = localX + margin;
            int gz = localZ + margin;
            if (gx < 0 || gx >= width || gz < 0 || gz >= width) {
                throw new IndexOutOfBoundsException("column " + localX + "," + localZ
                        + " is beyond the sampled margin of " + margin);
            }
            return gz * width + gx;
        }
    }
}
