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

    /** Resolves the terrain data for one column, sampling the DEM at that exact point. */
    TerrainSample sampleColumn(double latitude, double longitude);

    /**
     * Resolves one column whose block covers the given geographic footprint.
     *
     * <p>The elevation is the mean over that footprint rather than the value at its centre. At one
     * block per kilometre a block spans a dozen DEM samples in each axis, and picking one of them
     * contributes about a fifth of the block-to-block height difference all by itself -- roughness
     * the generator invents rather than reads. Averaging is deterministic and reads the same pages
     * the point sample would have touched.
     *
     * <p>Spans of zero mean "point sample", which is what {@link #sampleColumn(double, double)} and
     * every ad-hoc query ({@code /earth whereami}, the CLI) want: they ask about a place, not about
     * a block.
     */
    TerrainSample sampleColumn(double latitude, double longitude,
                               double latitudeSpanDegrees, double longitudeSpanDegrees);

    /** Resolves a whole chunk, using the chunk-level cache. */
    ChunkSampler.ChunkSamples sampleChunk(int chunkX, int chunkZ);

    /** Drops cached chunk data; called on reload. */
    void invalidate();
}
