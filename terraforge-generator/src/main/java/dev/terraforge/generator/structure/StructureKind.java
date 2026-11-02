package dev.terraforge.generator.structure;

import dev.terraforge.core.terrain.ClimateBiome;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.EntityType;
import org.bukkit.loot.LootTables;

/**
 * The structures TerraForge scatters over the world, each matched to the climate it stands in: a
 * reason to cross a continent and look over the next ridge.
 *
 * <p>All of them are ruins or remains rather than inhabited buildings -- the world is natural, and a
 * village at a kilometre per block would stand for a city. Each has loot from a vanilla table, so the
 * rewards follow the server's version and any datapack that changes them. Every block exists in 1.16.
 */
enum StructureKind {

    /** A broken stone-brick tower on open or wooded lowland; weapons in a chest at its foot. */
    WATCHTOWER(3, 4) {
        @Override
        void build(Builder b) {
            int height = 8 + b.random.nextInt(4);
            for (int dy = 1; dy <= height; dy++) {
                double keep = dy < height - 3 ? 0.92 : 0.92 - 0.2 * (dy - height + 4);
                for (int i = -2; i <= 2; i++) {
                    b.oldBrick(i, dy, -2, keep);
                    b.oldBrick(i, dy, 2, keep);
                    b.oldBrick(-2, dy, i, keep);
                    b.oldBrick(2, dy, i, keep);
                }
            }
            // A doorway, an upper floor that has partly fallen in, a lookout window.
            b.set(0, 1, -2, Material.AIR);
            b.set(0, 2, -2, Material.AIR);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    b.maybe(dx, 5, dz, Material.SPRUCE_PLANKS, 0.6);
                }
            }
            b.set(2, 6, 0, Material.AIR);
            b.set(-2, 6, 0, Material.AIR);
            b.facing(-1, 1, 1, Material.LADDER, BlockFace.NORTH);
            b.facing(-1, 2, 1, Material.LADDER, BlockFace.NORTH);
            b.facing(-1, 3, 1, Material.LADDER, BlockFace.NORTH);
            b.facing(-1, 4, 1, Material.LADDER, BlockFace.NORTH);
            b.container(1, 1, 1, Material.CHEST, BlockFace.WEST, LootTables.VILLAGE_WEAPONSMITH);
            b.set(0, 1, 0, Material.COBWEB);
            rubble(b, 3, Material.COBBLESTONE);
        }
    },

    /** Sand-drifted sandstone walls around a well; the chest is in a cellar under the floor. */
    DESERT_RUIN(4, 3) {
        @Override
        void build(Builder b) {
            for (int i = -4; i <= 4; i++) {
                int north = b.random.nextInt(4);
                int south = b.random.nextInt(4);
                int west = b.random.nextInt(4);
                int east = b.random.nextInt(4);
                for (int dy = 1; dy <= 3; dy++) {
                    if (dy <= north) b.set(i, dy, -4, wall(b));
                    if (dy <= south) b.set(i, dy, 4, wall(b));
                    if (dy <= west) b.set(-4, dy, i, wall(b));
                    if (dy <= east) b.set(4, dy, i, wall(b));
                }
            }
            for (int[] c : new int[][]{{-4, -4}, {4, -4}, {-4, 4}, {4, 4}}) {
                b.fill(c[0], 1, c[1], c[0], 3 + b.random.nextInt(2), c[1], Material.CUT_SANDSTONE);
                b.set(c[0], 4, c[1], Material.CHISELED_SANDSTONE);
            }
            // The well.
            b.fill(-1, 0, -1, 1, 0, 1, Material.SMOOTH_SANDSTONE);
            b.set(0, 0, 0, Material.WATER);
            b.set(0, -1, 0, Material.WATER);
            b.set(0, -2, 0, Material.SANDSTONE);
            for (int[] c : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                b.set(c[0], 1, c[1], Material.SANDSTONE_WALL);
            }
            // The cellar, under a loose slab of floor.
            b.fill(2, -4, 1, 3, -1, 3, Material.SANDSTONE);
            b.fill(2, -3, 2, 2, -1, 2, Material.AIR);
            b.set(2, 0, 2, slab(Material.SANDSTONE_SLAB, Slab.Type.BOTTOM));
            b.container(3, -3, 2, Material.CHEST, BlockFace.WEST, LootTables.DESERT_PYRAMID);
            b.set(2, -3, 2, Material.AIR);
            b.set(3, -2, 2, Material.AIR);
            rubble(b, 4, Material.SANDSTONE);
        }

        private static Material wall(Builder b) {
            return b.random.nextDouble() < 0.7 ? Material.SANDSTONE : Material.CUT_SANDSTONE;
        }
    },

    /** A stepped shrine of mossy stone in the rain forest, grown over with vines. */
    JUNGLE_SHRINE(4, 6) {
        @Override
        void build(Builder b) {
            for (int tier = 0; tier < 3; tier++) {
                int r = 4 - tier;
                for (int dz = -r; dz <= r; dz++) {
                    for (int dx = -r; dx <= r; dx++) {
                        if (Math.abs(dx) == r || Math.abs(dz) == r || tier == 2) {
                            b.set(dx, tier + 1, dz, b.random.nextDouble() < 0.5 ? Material.MOSSY_COBBLESTONE
                                    : b.random.nextDouble() < 0.5 ? Material.COBBLESTONE : Material.MOSSY_STONE_BRICKS);
                        }
                    }
                }
            }
            // Steps up the north face and a roofed altar on top.
            for (int tier = 0; tier < 3; tier++) {
                b.set(0, tier + 1, -4 + tier, stairs(Material.COBBLESTONE_STAIRS, BlockFace.SOUTH));
            }
            b.fill(-1, 4, -1, 1, 4, 1, Material.AIR);
            for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
                b.fill(c[0], 4, c[1], c[0], 6, c[1], Material.MOSSY_STONE_BRICKS);
            }
            b.fill(-2, 7, -2, 2, 7, 2, Material.MOSSY_STONE_BRICKS);
            b.set(0, 4, 0, Material.CHISELED_STONE_BRICKS);
            b.container(0, 5, 0, Material.CHEST, BlockFace.NORTH, LootTables.JUNGLE_TEMPLE);
            for (int i = 0; i < 24; i++) {
                int dx = b.random.nextInt(9) - 4;
                int dz = b.random.nextInt(9) - 4;
                int dy = 1 + b.random.nextInt(6);
                b.vine(dx, dy, dz, BlockFace.values()[b.random.nextInt(4)]);
            }
        }
    },

    /** Standing stones in a ring; something is buried under the flat stone at the centre. */
    STONE_CIRCLE(5, 4) {
        @Override
        void build(Builder b) {
            int stones = 7 + b.random.nextInt(4);
            for (int i = 0; i < stones; i++) {
                double angle = 2 * Math.PI * i / stones;
                int dx = (int) Math.round(Math.cos(angle) * 4.5);
                int dz = (int) Math.round(Math.sin(angle) * 4.5);
                int height = 1 + b.random.nextInt(4);
                Material stone = b.random.nextDouble() < 0.5 ? Material.ANDESITE
                        : b.random.nextDouble() < 0.5 ? Material.MOSSY_COBBLESTONE : Material.STONE;
                b.fill(dx, 1, dz, dx, height, dz, stone);
            }
            b.set(0, 0, 0, Material.SMOOTH_STONE);
            b.set(0, 1, 0, slab(Material.SMOOTH_STONE_SLAB, Slab.Type.BOTTOM));
            b.set(0, -1, 0, Material.GRAVEL);
            b.container(0, -2, 0, Material.CHEST, BlockFace.NORTH, LootTables.BURIED_TREASURE);
        }

        @Override
        Material floor(ClimateBiome biome) {
            return null; // the circle stands on the meadow itself
        }

        @Override
        Material foundation(ClimateBiome biome) {
            return natural(biome);
        }
    },

    /** An abandoned camp: a cold fire ring, tents, a log bench and a barrel of supplies. */
    CAMP(4, 4) {
        @Override
        void build(Builder b) {
            b.set(0, 1, 0, Material.CAMPFIRE.createBlockData(d -> ((org.bukkit.block.data.Lightable) d).setLit(false)));
            for (int[] c : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}, {0, -1}, {0, 1}, {-1, 0}, {1, 0}}) {
                b.maybe(c[0], 0, c[1], Material.COBBLESTONE, 0.7);
            }
            tent(b, -3, Material.WHITE_WOOL);
            if (b.random.nextBoolean()) {
                tent(b, 3, Material.BROWN_WOOL);
            }
            b.set(-1, 1, 3, Material.SPRUCE_LOG.createBlockData(d -> ((org.bukkit.block.data.Orientable) d)
                    .setAxis(org.bukkit.Axis.X)));
            b.set(0, 1, 3, Material.SPRUCE_LOG.createBlockData(d -> ((org.bukkit.block.data.Orientable) d)
                    .setAxis(org.bukkit.Axis.X)));
            b.container(2, 1, -2, Material.BARREL, BlockFace.UP, LootTables.VILLAGE_TAIGA_HOUSE);
            b.maybe(2, 1, 2, Material.HAY_BLOCK, 0.6);
            b.maybe(-2, 1, 2, Material.CRAFTING_TABLE, 0.5);
        }

        private static void tent(Builder b, int dx, Material wool) {
            for (int dz = -1; dz <= 1; dz++) {
                b.set(dx - 1, 1, dz, wool);
                b.set(dx + 1, 1, dz, wool);
                b.set(dx, 2, dz, wool);
            }
            b.set(dx, 1, -1, Material.AIR);
        }

        @Override
        Material floor(ClimateBiome biome) {
            return Material.COARSE_DIRT;
        }
    },

    /** A wreck washed up on a beach, its hull on its side; the cargo is still aboard. */
    SHIPWRECK(4, 5) {
        @Override
        void build(Builder b) {
            Material planks = b.random.nextBoolean() ? Material.OAK_PLANKS : Material.SPRUCE_PLANKS;
            Material stairs = planks == Material.OAK_PLANKS ? Material.OAK_STAIRS : Material.SPRUCE_STAIRS;
            // The hull runs along X, keel half sunk in the sand.
            for (int dx = -4; dx <= 4; dx++) {
                int half = Math.abs(dx) >= 4 ? 0 : Math.abs(dx) == 3 ? 1 : 2;
                for (int dz = -half; dz <= half; dz++) {
                    b.set(dx, 0, dz, planks);
                    if (Math.abs(dz) == half) {
                        b.maybe(dx, 1, dz, planks, 0.85);
                        b.maybe(dx, 2, dz, planks, Math.abs(dx) < 3 ? 0.6 : 0.3);
                    }
                }
            }
            b.set(-4, 1, 0, stairs(stairs, BlockFace.EAST));
            b.set(4, 1, 0, stairs(stairs, BlockFace.WEST));
            for (int dx = -2; dx <= 2; dx++) {
                b.maybe(dx, 2, 0, planks, 0.5); // what is left of the deck
            }
            // The mast lies broken across the sand.
            for (int dz = 2; dz <= 6; dz++) {
                b.maybe(0, 1, dz, Material.SPRUCE_LOG.createBlockData(d -> ((org.bukkit.block.data.Orientable) d)
                        .setAxis(org.bukkit.Axis.Z)), 0.8);
            }
            b.container(-2, 1, 0, Material.CHEST, BlockFace.EAST, LootTables.SHIPWRECK_SUPPLY);
            b.container(2, 1, 0, Material.CHEST, BlockFace.WEST, LootTables.SHIPWRECK_TREASURE);
        }

        @Override
        Material floor(ClimateBiome biome) {
            return null;
        }

        @Override
        Material foundation(ClimateBiome biome) {
            return Material.SAND;
        }
    },

    /** A shepherd's hut of stone and spruce high in the mountains. */
    MOUNTAIN_HUT(3, 5) {
        @Override
        void build(Builder b) {
            for (int dy = 1; dy <= 3; dy++) {
                for (int i = -2; i <= 2; i++) {
                    b.set(i, dy, -2, dy == 3 ? Material.SPRUCE_PLANKS : Material.COBBLESTONE);
                    b.set(i, dy, 2, dy == 3 ? Material.SPRUCE_PLANKS : Material.COBBLESTONE);
                    b.set(-2, dy, i, dy == 3 ? Material.SPRUCE_PLANKS : Material.COBBLESTONE);
                    b.set(2, dy, i, dy == 3 ? Material.SPRUCE_PLANKS : Material.COBBLESTONE);
                }
            }
            for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
                b.fill(c[0], 1, c[1], c[0], 3, c[1], Material.SPRUCE_LOG);
            }
            // A pitched roof, partly fallen in.
            for (int dx = -3; dx <= 3; dx++) {
                b.maybe(dx, 4, -2, stairs(Material.SPRUCE_STAIRS, BlockFace.SOUTH), 0.85);
                b.maybe(dx, 4, 2, stairs(Material.SPRUCE_STAIRS, BlockFace.NORTH), 0.85);
                b.maybe(dx, 4, -1, Material.SPRUCE_PLANKS, 0.8);
                b.maybe(dx, 4, 1, Material.SPRUCE_PLANKS, 0.8);
                b.maybe(dx, 5, 0, slab(Material.SPRUCE_SLAB, Slab.Type.BOTTOM), 0.85);
            }
            b.set(0, 1, -2, Material.AIR);
            b.set(0, 2, -2, Material.AIR);
            b.set(2, 2, 0, Material.AIR);
            b.set(-1, 1, 1, Material.CRAFTING_TABLE);
            b.set(1, 2, 1, Material.LANTERN);
            b.set(1, 1, 1, Material.SPRUCE_PLANKS);
            b.container(-1, 1, -1, Material.CHEST, BlockFace.EAST, LootTables.VILLAGE_SNOWY_HOUSE);
        }

        @Override
        Material floor(ClimateBiome biome) {
            return Material.SPRUCE_PLANKS;
        }
    },

    /** A snow-block shelter in the far north, with the last traveller's things inside. */
    SNOW_SHELTER(3, 4) {
        @Override
        void build(Builder b) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int d = Math.max(Math.abs(dx), Math.abs(dz));
                    if (d == 2) {
                        b.set(dx, 1, dz, Material.SNOW_BLOCK);
                        b.set(dx, 2, dz, Material.SNOW_BLOCK);
                    } else if (d == 1) {
                        b.set(dx, 3, dz, Material.SNOW_BLOCK);
                    } else {
                        b.set(dx, 4, dz, Material.SNOW_BLOCK);
                    }
                }
            }
            b.set(0, 1, -2, Material.AIR);
            b.set(0, 2, -2, Material.AIR);
            b.set(0, 1, 1, Material.WHITE_WOOL);
            b.set(-1, 1, 1, Material.WHITE_WOOL);
            b.container(1, 1, 1, Material.CHEST, BlockFace.WEST, LootTables.IGLOO_CHEST);
            b.set(1, 3, 0, Material.LANTERN.createBlockData(d -> ((org.bukkit.block.data.type.Lantern) d)
                    .setHanging(true)));
        }

        @Override
        Material floor(ClimateBiome biome) {
            return Material.PACKED_ICE;
        }
    },

    /**
     * A crypt ten blocks down: a well-head on the surface, a ladder shaft, and a vaulted room with a
     * spawner and two chests -- a dungeon with a door.
     */
    CRYPT(2, 6) {
        @Override
        void build(Builder b) {
            // The well-head that marks it.
            for (int[] c : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                b.fill(c[0], 1, c[1], c[0], 2, c[1], Material.MOSSY_STONE_BRICKS);
            }
            b.set(0, 1, -1, Material.STONE_BRICK_WALL);
            b.set(0, 1, 1, Material.STONE_BRICK_WALL);
            b.set(-1, 1, 0, Material.STONE_BRICK_WALL);
            b.set(1, 1, 0, Material.STONE_BRICK_WALL);
            b.fill(-1, 3, -1, 1, 3, 1, Material.STONE_BRICK_SLAB);
            b.set(0, 3, 0, Material.AIR);
            // The shaft: lined, with a ladder on its south wall, down to the room's ceiling.
            int depth = CRYPT_DEPTH;
            for (int dy = 0; dy >= -depth + 4; dy--) {
                for (int[] c : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                    b.oldBrick(c[0], dy, c[1], 0.97);
                }
                b.set(0, dy, 0, Material.AIR);
                b.facing(0, dy, 0, Material.LADDER, BlockFace.NORTH);
            }
            // The room.
            int floor = -depth;
            for (int dy = floor; dy <= floor + 4; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dx = -4; dx <= 4; dx++) {
                        boolean shell = dy == floor || dy == floor + 4 || Math.abs(dx) == 4 || Math.abs(dz) == 3;
                        if (shell) {
                            b.oldBrick(dx, dy, dz, 1.0);
                        } else {
                            b.set(dx, dy, dz, Material.CAVE_AIR);
                        }
                    }
                }
            }
            for (int dy = floor + 1; dy <= floor + 3; dy++) {
                b.facing(0, dy, 0, Material.LADDER, BlockFace.NORTH);
            }
            b.set(0, floor + 4, 0, Material.AIR);
            b.facing(0, floor + 4, 0, Material.LADDER, BlockFace.NORTH);
            b.spawner(-2, floor + 1, 0, b.random.nextBoolean() ? EntityType.ZOMBIE
                    : b.random.nextBoolean() ? EntityType.SKELETON : EntityType.SPIDER);
            b.container(-3, floor + 1, -2, Material.CHEST, BlockFace.EAST, LootTables.SIMPLE_DUNGEON);
            b.container(3, floor + 1, 2, Material.CHEST, BlockFace.WEST, LootTables.SIMPLE_DUNGEON);
            for (int i = 0; i < 6; i++) {
                b.maybe(b.random.nextInt(7) - 3, floor + 3, b.random.nextInt(5) - 2, Material.COBWEB, 0.8);
            }
            b.set(3, floor + 1, -2, Material.BONE_BLOCK);
            b.set(3, floor + 2, -2, Material.SKELETON_SKULL);
        }

        @Override
        Material floor(ClimateBiome biome) {
            return null;
        }

        @Override
        Material foundation(ClimateBiome biome) {
            return natural(biome);
        }
    };

    /** How far below the surface a crypt's floor lies. */
    static final int CRYPT_DEPTH = 11;

    /** Half the edge of the square the structure stands on. */
    final int radius;
    /** Blocks cleared above the floor. */
    final int headroom;

    StructureKind(int radius, int headroom) {
        this.radius = radius;
        this.headroom = headroom;
    }

    abstract void build(Builder b);

    /** The floor laid over the levelled square, or {@code null} to keep the ground as it is. */
    Material floor(ClimateBiome biome) {
        return switch (biome) {
            case DESERT, SEMI_DESERT -> Material.SANDSTONE;
            default -> Material.COBBLESTONE;
        };
    }

    /** What fills the ground up to the floor on a slope. */
    Material foundation(ClimateBiome biome) {
        return switch (biome) {
            case DESERT, SEMI_DESERT -> Material.SANDSTONE;
            default -> Material.COBBLESTONE;
        };
    }

    /** The ground a natural surface would have here, for structures that stand on it. */
    static Material natural(ClimateBiome biome) {
        return switch (biome) {
            case DESERT, SEMI_DESERT, BEACH -> Material.SAND;
            case TUNDRA, GLACIER -> Material.SNOW_BLOCK;
            case ALPINE, BARE_ROCK, STONY_SHORE -> Material.STONE;
            default -> Material.GRASS_BLOCK;
        };
    }

    /**
     * Which structure suits this site, or {@code null} for none. {@code roll} is uniform in
     * {@code [0, 1)} and fixed for the site.
     */
    static StructureKind forSite(ClimateBiome biome, boolean coast, double roll) {
        if (coast && (biome == ClimateBiome.BEACH || biome == ClimateBiome.STONY_SHORE)) {
            return SHIPWRECK;
        }
        return switch (biome) {
            case DESERT, SEMI_DESERT -> roll < 0.7 ? DESERT_RUIN : CRYPT;
            case TROPICAL_RAINFOREST, TROPICAL_SEASONAL_FOREST, MANGROVE -> roll < 0.75 ? JUNGLE_SHRINE : CRYPT;
            case SAVANNA, SHRUBLAND -> roll < 0.5 ? CAMP : roll < 0.75 ? WATCHTOWER : CRYPT;
            case GRASSLAND, TEMPERATE_FOREST, TEMPERATE_RAINFOREST, WETLAND ->
                    roll < 0.4 ? WATCHTOWER : roll < 0.7 ? CRYPT : STONE_CIRCLE;
            case BOREAL_FOREST -> roll < 0.4 ? CAMP : roll < 0.7 ? STONE_CIRCLE : CRYPT;
            case TUNDRA, GLACIER -> roll < 0.7 ? SNOW_SHELTER : STONE_CIRCLE;
            case MOUNTAIN_FOREST, MOUNTAIN_MEADOW -> roll < 0.5 ? MOUNTAIN_HUT : roll < 0.75 ? WATCHTOWER : CRYPT;
            case ALPINE, BARE_ROCK -> roll < 0.6 ? MOUNTAIN_HUT : CRYPT;
            default -> null;
        };
    }

    /** Fallen blocks scattered on the ground around a ruin. */
    static void rubble(Builder b, int radius, Material material) {
        for (int i = 0; i < 8; i++) {
            int dx = b.random.nextInt(2 * radius + 3) - radius - 1;
            int dz = b.random.nextInt(2 * radius + 3) - radius - 1;
            if (Math.abs(dx) > radius - 1 || Math.abs(dz) > radius - 1) {
                b.maybe(dx, 1, dz, material, 0.7);
            }
        }
    }

    static org.bukkit.block.data.BlockData stairs(Material material, BlockFace face) {
        Stairs stairs = (Stairs) material.createBlockData();
        stairs.setFacing(face);
        stairs.setHalf(Bisected.Half.BOTTOM);
        return stairs;
    }

    static org.bukkit.block.data.BlockData slab(Material material, Slab.Type type) {
        Slab slab = (Slab) material.createBlockData();
        slab.setType(type);
        return slab;
    }
}
