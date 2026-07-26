package dev.terraforge.generator.pipeline;

import dev.terraforge.core.terrain.TerrainSample;

/**
 * The ordered stages that turn geodata into a chunk.
 *
 * <pre>
 *   chunk coords -> geographic bounds -> DEM lookup -> interpolation
 *                -> water classification -> biome resolution -> terrain shaping
 *                -> surface materials -> natural vegetation
 * </pre>
 *
 * <p>Stages are separate so each can be tested and benchmarked on its own, and so a stage can be
 * swapped (a different biome mapper, a different surface rule set) without touching the rest.
 *
 * <p><strong>Invariant:</strong> no stage may place a man-made structure. There is no stage for
 * roads, buildings, railways or bridges, and none may be added -- that is the whole point of the
 * project.
 */
public interface TerrainPipeline {

    /** Resolves the terrain data for one column. */
    TerrainSample sampleColumn(double latitude, double longitude);

    /** Resolves a whole chunk, using the chunk-level cache. */
    ChunkSampler.ChunkSamples sampleChunk(int chunkX, int chunkZ);

    /** Drops cached chunk data; called on reload. */
    void invalidate();
}
