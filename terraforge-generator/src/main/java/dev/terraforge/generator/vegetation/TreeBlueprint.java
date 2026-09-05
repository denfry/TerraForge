package dev.terraforge.generator.vegetation;

import java.util.List;
import org.bukkit.Material;

/**
 * A procedural tree, as blocks relative to the ground it stands on, with no world attached.
 *
 * <p>Vanilla's {@code TreeType} features cover the everyday species. What they lack is the trees
 * that make a place recognisable -- a willow trailing into a river, a palm leaning over a warm
 * beach, a baobab on the savanna, the bare trunk of a dead tree on the steppe -- and those are
 * drawn by {@link TreeShapes}. The blueprint is pure data so the shapes can be tested for size
 * and determinism without a server; the populator turns it into blocks.
 *
 * @param wood   the species' log and leaf blocks
 * @param voxels every block of the tree; {@code dy == 1} is the block directly above the ground
 */
public record TreeBlueprint(Wood wood, List<Voxel> voxels) {

    /** The log and leaf blocks of a species. */
    public record Wood(Material log, Material leaves) {
        public static final Wood OAK = new Wood(Material.OAK_LOG, Material.OAK_LEAVES);
        public static final Wood BIRCH = new Wood(Material.BIRCH_LOG, Material.BIRCH_LEAVES);
        public static final Wood SPRUCE = new Wood(Material.SPRUCE_LOG, Material.SPRUCE_LEAVES);
        public static final Wood JUNGLE = new Wood(Material.JUNGLE_LOG, Material.JUNGLE_LEAVES);
        public static final Wood ACACIA = new Wood(Material.ACACIA_LOG, Material.ACACIA_LEAVES);
        public static final Wood DARK_OAK = new Wood(Material.DARK_OAK_LOG, Material.DARK_OAK_LEAVES);
        public static final Wood MANGROVE = new Wood(Material.MANGROVE_LOG, Material.MANGROVE_LEAVES);
    }

    /** What a voxel of the tree is made of; the material follows from the wood. */
    public enum Kind {
        /** A log standing upright. */
        LOG_Y,
        /** A log lying along X. */
        LOG_X,
        /** A log lying along Z. */
        LOG_Z,
        /** Persistent leaves: never decay, whatever they touch. */
        LEAVES,
        /** A moss carpet on top of a fallen trunk. */
        MOSS,
        /** A brown mushroom on a rotting trunk. */
        MUSHROOM;

        /** Logs may replace leaves and plants; leaves and carpets only fill air. */
        public boolean isLog() {
            return this == LOG_Y || this == LOG_X || this == LOG_Z;
        }
    }

    /** One block of the tree, relative to the ground block under the trunk. */
    public record Voxel(int dx, int dy, int dz, Kind kind) {
    }

    /** The largest horizontal reach of any voxel, in blocks. */
    public int radius() {
        int r = 0;
        for (Voxel v : voxels) {
            r = Math.max(r, Math.max(Math.abs(v.dx()), Math.abs(v.dz())));
        }
        return r;
    }

    /** The highest voxel, in blocks above the ground. */
    public int height() {
        int h = 0;
        for (Voxel v : voxels) {
            h = Math.max(h, v.dy());
        }
        return h;
    }
}
