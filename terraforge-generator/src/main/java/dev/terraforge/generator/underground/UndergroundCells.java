package dev.terraforge.generator.underground;

import dev.terraforge.generator.pipeline.ChunkSampler;
import dev.terraforge.generator.surface.SurfacePalette;

/**
 * One chunk's stone, as a byte per block, worked on before anything is written to the chunk.
 *
 * <p>The generator already knows exactly where the stone is: from the bedrock floor up to the soil
 * {@link SurfacePalette} lays under each column's surface. So the model is built from the samples
 * rather than read back block by block from the chunk, and everything the passes do -- pockets,
 * caves, ores -- is decided here and written once. Soil, water, air and bedrock are outside the model
 * and can never be touched.
 */
final class UndergroundCells {

    /** Not stone: soil, water, air or bedrock. Never changed. */
    static final byte OTHER = 0;
    static final byte STONE = 1;
    static final byte CARVED = 2;
    private static final byte MINERAL_BASE = 16;

    private final int floorY;
    private final int topY;
    private final int[] stoneTop;
    private final byte[] cells;

    private UndergroundCells(int floorY, int topY, int[] stoneTop) {
        this.floorY = floorY;
        this.topY = topY;
        this.stoneTop = stoneTop;
        this.cells = new byte[Math.max(0, topY - floorY) << 8];
        for (int column = 0; column < 256; column++) {
            for (int y = floorY; y < stoneTop[column]; y++) {
                cells[((y - floorY) << 8) | column] = STONE;
            }
        }
    }

    /**
     * The stone the generator laid for this chunk.
     *
     * @param floorY   lowest Y above the bedrock
     * @param ceilingY highest Y a surface may take ({@code maxHeight - 1})
     */
    static UndergroundCells of(ChunkSampler.ChunkSamples samples, int floorY, int ceilingY) {
        int[] stoneTop = new int[256];
        int highest = floorY;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                // Mirrors generateNoise/generateSurface exactly: stone up to the surface, soil over its
                // top FILLER_DEPTH blocks.
                int surfaceY = Math.clamp(samples.at(localX, localZ).surfaceY(), floorY, ceilingY);
                int top = Math.max(floorY, surfaceY - SurfacePalette.FILLER_DEPTH);
                stoneTop[localZ << 4 | localX] = top;
                highest = Math.max(highest, top);
            }
        }
        return new UndergroundCells(floorY, highest, stoneTop);
    }

    int floorY() {
        return floorY;
    }

    /** One above the highest stone block in the chunk. */
    int topY() {
        return topY;
    }

    byte get(int localX, int y, int localZ) {
        if (y < floorY || y >= topY) {
            return OTHER;
        }
        return cells[index(localX, y, localZ)];
    }

    void set(int localX, int y, int localZ, byte code) {
        cells[index(localX, y, localZ)] = code;
    }

    /** Whether a pocket or an ore may replace what is here: plain stone, granite, diorite or andesite. */
    static boolean hostsMineral(byte code) {
        return code == STONE || (code >= MINERAL_BASE && mineralOf(code).hostRock());
    }

    /** Whether a cave may carve through what is here: any stone, pocket or ore, never soil or water. */
    static boolean carvable(byte code) {
        return code != OTHER && code != CARVED;
    }

    static byte code(Mineral mineral) {
        return (byte) (MINERAL_BASE + mineral.ordinal());
    }

    static Mineral mineralOf(byte code) {
        return Mineral.values()[code - MINERAL_BASE];
    }

    /** Hands every block the passes changed to {@code sink}; plain stone is already in the chunk. */
    int writeTo(UndergroundGenerator.BlockSink sink, org.bukkit.Material caveAir) {
        int written = 0;
        for (int index = 0; index < cells.length; index++) {
            byte code = cells[index];
            if (code == OTHER || code == STONE) {
                continue;
            }
            int y = floorY + (index >>> 8);
            int localZ = (index >>> 4) & 15;
            int localX = index & 15;
            sink.set(localX, y, localZ, code == CARVED ? caveAir : mineralOf(code).material());
            written++;
        }
        return written;
    }

    private int index(int localX, int y, int localZ) {
        return ((y - floorY) << 8) | (localZ << 4) | localX;
    }
}
