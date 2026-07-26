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
     * @param chunkX  chunk X
     * @param chunkZ  chunk Z
     * @param bounds  geographic bounds of the chunk
     * @param samples 256 samples, row-major {@code z * 16 + x}
     */
    record ChunkSamples(int chunkX, int chunkZ, GeoBounds bounds, TerrainSample[] samples) {

        public static final int SIZE = 16;

        public ChunkSamples {
            if (samples.length != SIZE * SIZE) {
                throw new IllegalArgumentException("expected " + (SIZE * SIZE) + " samples, got " + samples.length);
            }
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
}
