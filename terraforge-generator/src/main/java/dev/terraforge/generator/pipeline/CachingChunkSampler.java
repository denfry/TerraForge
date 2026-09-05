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
 * rather than arguing over the shared edge. The centre locates the block; the block's whole
 * geographic footprint decides its height, so a kilometre-wide block reports the mean of the
 * ground it covers instead of whichever DEM sample happens to sit under its middle.
 */
public final class CachingChunkSampler implements ChunkSampler {

    private final TerrainPipeline pipeline;
    private final CoordinateTransformer transformer;
    private final ManagedCache<Long, ChunkSamples> cache;
    private final double smoothing;
    private final int shoreReach;

    /** Averages exactly the block's own ground: no smoothing. */
    public CachingChunkSampler(TerrainPipeline pipeline, CoordinateTransformer transformer,
                               CacheManager cacheManager, int maxChunks) {
        this(pipeline, transformer, cacheManager, maxChunks, 1.0);
    }

    /**
     * @param smoothing width, in blocks, of the ground each column's elevation is averaged over
     *                  ({@code terrain.smoothing}). {@code 1.0} is the block's own footprint;
     *                  {@code 3.0} a window one block wider on every side. Because every column
     *                  computes its own window from its own centre, the filter is seamless across
     *                  chunk borders and needs no neighbouring chunk.
     */
    public CachingChunkSampler(TerrainPipeline pipeline, CoordinateTransformer transformer,
                               CacheManager cacheManager, int maxChunks, double smoothing) {
        this(pipeline, transformer, cacheManager, maxChunks, smoothing, 0);
    }

    /**
     * @param shoreReach how many columns a shore's influence extends ({@code water.shore-blend-blocks}):
     *                   banks climb at most one block per block for that far from the water, and
     *                   beds shelve out one block per block for that far from the land. {@code 0}
     *                   leaves shores as the data has them. Each chunk samples a margin that wide
     *                   around itself so the result does not depend on which chunk asks.
     */
    public CachingChunkSampler(TerrainPipeline pipeline, CoordinateTransformer transformer,
                               CacheManager cacheManager, int maxChunks, double smoothing, int shoreReach) {
        if (!(smoothing >= 1.0) || !Double.isFinite(smoothing)) {
            throw new IllegalArgumentException("smoothing must be at least 1.0: " + smoothing);
        }
        if (shoreReach < 0) {
            throw new IllegalArgumentException("shore reach must not be negative: " + shoreReach);
        }
        this.pipeline = pipeline;
        this.transformer = transformer;
        this.cache = cacheManager.newCache("chunk-samples", maxChunks);
        this.smoothing = smoothing;
        this.shoreReach = shoreReach;
    }

    @Override
    public ChunkSamples sample(int chunkX, int chunkZ) {
        return cache.get(key(chunkX, chunkZ), ignored -> compute(chunkX, chunkZ));
    }

    private ChunkSamples compute(int chunkX, int chunkZ) {
        GeoBounds bounds = transformer.chunkBounds(chunkX, chunkZ);
        // The ground one block covers, in degrees. Derived from the chunk's own bounds because every
        // supported projection is linear (or very nearly so) across sixteen blocks, which makes this
        // two projections per chunk instead of two per column.
        // Widened by the smoothing factor: overlapping footprints are a box filter over the height
        // field, which removes the block-to-block steps a kilometre-wide column otherwise shows.
        double latitudeSpan = bounds.latitudeSpan() / ChunkSamples.SIZE * smoothing;
        double longitudeSpan = bounds.longitudeSpan() / ChunkSamples.SIZE * smoothing;
        double baseX = chunkX * 16.0;
        double baseZ = chunkZ * 16.0;
        // The chunk plus a margin of shoreReach columns on every side, so a shore just over the
        // border shapes this chunk's edge exactly as it shapes the neighbour's.
        int margin = shoreReach;
        int width = ChunkSamples.SIZE + 2 * margin;
        TerrainSample[] grid = new TerrainSample[width * width];
        for (int gz = 0; gz < width; gz++) {
            for (int gx = 0; gx < width; gx++) {
                GeoPoint point = transformer.toGeographic(baseX + gx - margin + 0.5, baseZ + gz - margin + 0.5);
                grid[gz * width + gx] = pipeline.sampleColumn(
                        point.latitude(), point.longitude(), latitudeSpan, longitudeSpan);
            }
        }
        ShoreShaper.shape(grid, width, shoreReach, margin);
        if (margin == 0) {
            return new ChunkSamples(chunkX, chunkZ, bounds, grid);
        }
        TerrainSample[] samples = new TerrainSample[ChunkSamples.SIZE * ChunkSamples.SIZE];
        for (int localZ = 0; localZ < ChunkSamples.SIZE; localZ++) {
            System.arraycopy(grid, (localZ + margin) * width + margin, samples, localZ * ChunkSamples.SIZE,
                    ChunkSamples.SIZE);
        }
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
