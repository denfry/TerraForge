package dev.terraforge.plugin.world;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Takes the spawners out of the Nether paired with the TerraForge world
 * ({@code world.nether.spawners: false}).
 *
 * <p>Vanilla builds a blaze spawner into every fortress and a magma cube spawner into a bastion's
 * treasure room, in code a datapack cannot reach. So each Nether chunk is checked as it loads -- new
 * ones and ones generated before this was turned on -- and any spawner in it becomes air. The check
 * asks the chunk's block palettes first, so a chunk without a spawner costs next to nothing. Blazes
 * still spawn in fortresses on their own, so blaze rods stay obtainable.
 *
 * <p>The event comes on the thread that owns the chunk, on Folia its region's, so the block is changed
 * where it may be. A spawner a player or an admin places in the Nether goes the same way once its
 * chunk reloads.
 */
public final class NetherSpawnerRemover implements Listener {

    private final String netherName;

    /** @param overworldName the TerraForge world, whose Nether the server names {@code <name>_nether} */
    public NetherSpawnerRemover(String overworldName) {
        this.netherName = overworldName + "_nether";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (world.getEnvironment() != World.Environment.NETHER || !world.getName().equals(netherName)) {
            return;
        }
        Chunk chunk = event.getChunk();
        // The block palette says whether there is a spawner at all; only a fortress or bastion chunk
        // is then walked block by block. Not getTileEntities(): a block entity that came with the
        // generated chunk is created on first access, so a fresh fortress spawner is not listed yet.
        if (!chunk.contains(SPAWNER)) {
            return;
        }
        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (block.getType() == Material.SPAWNER) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private static final BlockData SPAWNER = Material.SPAWNER.createBlockData();
}
