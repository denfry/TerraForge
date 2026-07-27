package dev.terraforge.generator.pipeline;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.cache.CacheStatistics;
import dev.terraforge.core.cache.ManagedCache;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.terrain.TerrainSample;

/**
 * Samples a chunk's 256 columns through the pipeline and caches the result.
 *
 * <p>Paper asks for the same chunk more than once -- noise, surface, biomes -- and a neighbouring
 * chunk's edge often lands on the same DEM tile page. Sampling once per chunk and keeping the
 * result turns those repeats into a map lookup instead of 256 more geodata queries.
 *
 * <p>Column centres, not corners: sampling the middle of each block keeps a chunk's terrain
 * continuous with its neighbour's, since two adjacent chunks then sample two different points
 * rather than arguing over the shared edge.
 */
public final class CachingChunkSampler implements ChunkSampler {

    private final TerrainPipeline pipeline;
    private final CoordinateTransformer transformer;
    private final ManagedCache<Long, ChunkSamples> cache;

    public CachingChunkSampler(TerrainPipeline pipeline, CoordinateTransformer transformer,
                               CacheManager cacheManager, int maxChunks) {
        this.pipeline = pipeline;
        this.transformer = transformer;
        this.cache = cacheManager.newCache("chunk-samples", maxChunks);
    }

    @Override
    public ChunkSamples sample(int chunkX, int chunkZ) {
        return cache.get(key(chunkX, chunkZ), ignored -> compute(chunkX, chunkZ));
    }

    private ChunkSamples compute(int chunkX, int chunkZ) {
        TerrainSample[] samples = new TerrainSample[ChunkSamples.SIZE * ChunkSamples.SIZE];
        double baseX = chunkX * 16.0;
        double baseZ = chunkZ * 16.0;
        for (int localZ = 0; localZ < ChunkSamples.SIZE; localZ++) {
            for (int localX = 0; localX < ChunkSamples.SIZE; localX++) {
                GeoPoint point = transformer.toGeographic(baseX + localX + 0.5, baseZ + localZ + 0.5);
                samples[localZ * ChunkSamples.SIZE + localX] =
                        pipeline.sampleColumn(point.latitude(), point.longitude());
            }
        }
        GeoBounds bounds = transformer.chunkBounds(chunkX, chunkZ);
        return new ChunkSamples(chunkX, chunkZ, bounds, samples);
    }

    /** Chunk coordinates packed into one long, so a lookup costs one boxed key and no record. */
    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFF_FFFFL);
    }

    public CacheStatistics statistics() {
        return cache.statistics();
    }

    public void invalidate() {
        cache.invalidateAll();
    }
}
