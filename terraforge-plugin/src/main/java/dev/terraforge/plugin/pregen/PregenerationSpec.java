package dev.terraforge.plugin.pregen;

/** Immutable block-space request with inclusive derived chunk bounds. */
public record PregenerationSpec(int centerBlockX, int centerBlockZ, int radiusBlocks,
                                int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
                                long totalChunks) {
    public PregenerationSpec {
        if (radiusBlocks < 0 || minChunkX > maxChunkX || minChunkZ > maxChunkZ || totalChunks < 1) {
            throw new IllegalArgumentException("invalid pregeneration bounds");
        }
        long expected = Math.multiplyExact((long) maxChunkX - minChunkX + 1,
                (long) maxChunkZ - minChunkZ + 1);
        if (expected != totalChunks) {
            throw new IllegalArgumentException("pregeneration chunk count does not match its bounds");
        }
    }

    public static PregenerationSpec around(int centerBlockX, int centerBlockZ, int radiusBlocks) {
        if (radiusBlocks < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        long minX = (long) centerBlockX - radiusBlocks;
        long maxX = (long) centerBlockX + radiusBlocks;
        long minZ = (long) centerBlockZ - radiusBlocks;
        long maxZ = (long) centerBlockZ + radiusBlocks;
        if (minX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE
                || minZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("pregeneration block bounds overflow");
        }
        int minChunkX = Math.floorDiv((int) minX, 16);
        int maxChunkX = Math.floorDiv((int) maxX, 16);
        int minChunkZ = Math.floorDiv((int) minZ, 16);
        int maxChunkZ = Math.floorDiv((int) maxZ, 16);
        long total = Math.multiplyExact((long) maxChunkX - minChunkX + 1,
                (long) maxChunkZ - minChunkZ + 1);
        return new PregenerationSpec(centerBlockX, centerBlockZ, radiusBlocks,
                minChunkX, maxChunkX, minChunkZ, maxChunkZ, total);
    }

    public boolean contains(SpiralCursor.Chunk chunk) {
        return chunk.x() >= minChunkX && chunk.x() <= maxChunkX
                && chunk.z() >= minChunkZ && chunk.z() <= maxChunkZ;
    }

    /** The chunk the spiral must be centered on, derived from the block-space center. */
    public int centerChunkX() {
        return Math.floorDiv(centerBlockX, 16);
    }

    /** The chunk the spiral must be centered on, derived from the block-space center. */
    public int centerChunkZ() {
        return Math.floorDiv(centerBlockZ, 16);
    }
}
