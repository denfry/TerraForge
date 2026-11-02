package dev.terraforge.generator.structure;

import java.util.Random;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.entity.EntityType;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.loot.LootTables;
import org.bukkit.loot.Lootable;

/**
 * Writes one structure into a populator's region, in coordinates relative to its origin.
 *
 * <p>The origin is the floor: {@code dy = 0} is the floor block, {@code dy = 1} the first block a
 * player stands in. Every write is checked against the region and the world's height, so a structure
 * near an edge loses a few blocks instead of throwing -- Paper stops the server on an exception out of
 * a populator.
 *
 * <p>Only blocks a 1.16 client knows are used anywhere in this package: players on old versions
 * join through ViaBackwards, which draws newer blocks as substitutes.
 */
final class Builder {

    private final LimitedRegion region;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final int minY;
    private final int maxY;
    final Random random;

    Builder(LimitedRegion region, int originX, int originY, int originZ, int minY, int maxY, Random random) {
        this.region = region;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.minY = minY;
        this.maxY = maxY;
        this.random = random;
    }

    int floorY() {
        return originY;
    }

    private boolean inside(int x, int y, int z) {
        return y > minY && y <= maxY && region.isInRegion(x, y, z);
    }

    void set(int dx, int dy, int dz, Material material) {
        int x = originX + dx;
        int y = originY + dy;
        int z = originZ + dz;
        if (inside(x, y, z)) {
            region.setType(x, y, z, material);
        }
    }

    void set(int dx, int dy, int dz, BlockData data) {
        int x = originX + dx;
        int y = originY + dy;
        int z = originZ + dz;
        if (inside(x, y, z)) {
            region.setBlockData(x, y, z, data);
        }
    }

    Material get(int dx, int dy, int dz) {
        int x = originX + dx;
        int y = originY + dy;
        int z = originZ + dz;
        return inside(x, y, z) ? region.getType(x, y, z) : Material.AIR;
    }

    void fill(int x0, int y0, int z0, int x1, int y1, int z1, Material material) {
        for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
                    set(x, y, z, material);
                }
            }
        }
    }

    /** Places the block with probability {@code keep}, leaving what is there otherwise. */
    void maybe(int dx, int dy, int dz, Material material, double keep) {
        if (random.nextDouble() < keep) {
            set(dx, dy, dz, material);
        }
    }

    void maybe(int dx, int dy, int dz, BlockData data, double keep) {
        if (random.nextDouble() < keep) {
            set(dx, dy, dz, data);
        }
    }

    /** Stone bricks with age: some mossy, some cracked, some fallen out. */
    void oldBrick(int dx, int dy, int dz, double keep) {
        if (random.nextDouble() >= keep) {
            return;
        }
        double roll = random.nextDouble();
        set(dx, dy, dz, roll < 0.3 ? Material.MOSSY_STONE_BRICKS
                : roll < 0.5 ? Material.CRACKED_STONE_BRICKS
                : roll < 0.6 ? Material.MOSSY_COBBLESTONE
                : Material.STONE_BRICKS);
    }

    void facing(int dx, int dy, int dz, Material material, BlockFace face) {
        BlockData data = material.createBlockData();
        if (data instanceof Directional directional && directional.getFaces().contains(face)) {
            directional.setFacing(face);
        }
        set(dx, dy, dz, data);
    }

    /** Vines hanging on the given face of the block they grow in. */
    void vine(int dx, int dy, int dz, BlockFace onFace) {
        if (get(dx, dy, dz) != Material.AIR) {
            return;
        }
        MultipleFacing vine = (MultipleFacing) Material.VINE.createBlockData();
        vine.setFace(onFace, true);
        set(dx, dy, dz, vine);
    }

    /** A chest (or barrel) that fills from a vanilla loot table when a player first opens it. */
    void container(int dx, int dy, int dz, Material material, BlockFace face, LootTables table) {
        int x = originX + dx;
        int y = originY + dy;
        int z = originZ + dz;
        if (!inside(x, y, z)) {
            return;
        }
        facing(dx, dy, dz, material, face);
        BlockState state = region.getBlockState(x, y, z);
        if (state instanceof Lootable lootable) {
            lootable.setLootTable(table.getLootTable(), random.nextLong());
            // Through the region: BlockState#update would write to the world from a generation thread.
            region.setBlockState(x, y, z, state);
        }
    }

    void spawner(int dx, int dy, int dz, EntityType type) {
        int x = originX + dx;
        int y = originY + dy;
        int z = originZ + dz;
        if (!inside(x, y, z)) {
            return;
        }
        region.setType(x, y, z, Material.SPAWNER);
        if (region.getBlockState(x, y, z) instanceof CreatureSpawner spawner) {
            spawner.setSpawnedType(type);
            region.setBlockState(x, y, z, spawner);
        }
    }

    /**
     * Levels a square of half-width {@code radius} to the floor: fills any ground below it with
     * {@code foundation}, lays the floor unless it is {@code null}, and clears {@code headroom} blocks above it.
     */
    void level(int radius, int headroom, Material foundation, Material floor, int[][] ground) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int g = ground[dz + radius][dx + radius] - originY;
                for (int dy = Math.min(g + 1, 0); dy < 0; dy++) {
                    set(dx, dy, dz, foundation);
                }
                if (floor != null) {
                    set(dx, 0, dz, floor);
                } else if (!get(dx, 0, dz).isSolid() || g > 0) {
                    // The ground dips here and is filled to the floor, or rose above it and was cut
                    // back to subsoil: either way it gets the surface it would have had.
                    set(dx, 0, dz, foundation);
                }
                for (int dy = 1; dy <= headroom; dy++) {
                    Material here = get(dx, dy, dz);
                    if (here != Material.AIR && here != Material.WATER) {
                        set(dx, dy, dz, Material.AIR);
                    }
                }
            }
        }
    }
}
