package dev.terraforge.generator.underground;

import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.generator.pipeline.ChunkSampler;
import java.util.List;
import org.bukkit.Material;

/**
 * Everything under the ground: stone pockets, cave systems and ore veins, in that order -- vanilla's
 * order, so caves cut through pockets and ores appear in cave walls but never float in cave air.
 *
 * <p>Runs inside chunk generation, on Paper's generation threads, against a model of the chunk's
 * stone built from the samples the generator already holds; the chunk sees one write per changed
 * block. Nothing is patched into a live world afterwards, so nothing costs the server thread, sends
 * block updates to players or relights a loaded chunk.
 *
 * <p>Deterministic: every decision is a hash of block and chunk coordinates, never the world seed or
 * the clock. Thread-safe and immutable.
 */
public final class UndergroundGenerator {

    /** Receives each changed block; the generator backs it with {@code ChunkData}. */
    @FunctionalInterface
    public interface BlockSink {
        void set(int localX, int y, int localZ, Material material);
    }

    private final VeinPlacer variety;
    private final WormCaves caves;
    private final VeinPlacer ores;

    /**
     * @param settings {@code generation.underground}
     * @param floorY   lowest Y above the bedrock
     * @param seaLevel this world's sea level
     * @param topY     one above the highest buildable Y
     */
    public UndergroundGenerator(TerraForgeConfig.UndergroundSection settings, int floorY, int seaLevel, int topY) {
        this.variety = settings.stoneVariety()
                ? new VeinPlacer(OreTable.stoneVariety(floorY, seaLevel, topY), 1.0) : null;
        this.caves = settings.caves() ? new WormCaves(settings.caveRarity(), floorY, seaLevel) : null;
        List<OreBand> oreBands = OreTable.ores(floorY, seaLevel, topY);
        this.ores = settings.ores() ? new VeinPlacer(oreBands, settings.oreMultiplier()) : null;
    }

    /** Whether any pass is on; a generator with none is never asked to run. */
    public boolean enabled() {
        return variety != null || caves != null || ores != null;
    }

    /**
     * Generates one chunk's underground and hands every changed block to {@code sink}.
     *
     * @param floorY   lowest Y above the bedrock, as the chunk was built
     * @param ceilingY highest Y a surface may take ({@code maxHeight - 1})
     * @return how many blocks changed
     */
    public int generate(ChunkSampler.ChunkSamples samples, int floorY, int ceilingY, BlockSink sink) {
        UndergroundCells cells = UndergroundCells.of(samples, floorY, ceilingY);
        int chunkX = samples.chunkX();
        int chunkZ = samples.chunkZ();
        if (variety != null) {
            variety.place(chunkX, chunkZ, cells, samples);
        }
        if (caves != null) {
            caves.carve(chunkX, chunkZ, cells, samples);
        }
        if (ores != null) {
            ores.place(chunkX, chunkZ, cells, samples);
        }
        return cells.writeTo(sink, Material.CAVE_AIR);
    }
}
