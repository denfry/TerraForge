package dev.terraforge.generator.vegetation;

import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.generator.noise.CellNoise;
import dev.terraforge.generator.vegetation.TreeBlueprint.Wood;
import dev.terraforge.generator.vegetation.TreeShapes.Shape;
import java.util.Random;
import org.bukkit.Material;
import org.bukkit.TreeType;

/**
 * Decides what, if anything, grows on one column.
 *
 * <p>Pure: a {@link Column} and a random draw go in, a {@link Placement} comes out. No Bukkit
 * world is touched, which is what makes the calibration testable without a server.
 *
 * <p>Three inputs rank above the rest. Real land cover decides <em>how much</em> grows -- a
 * WorldCover tree-cover pixel is a forest whatever the climate model thinks, a cropland pixel a
 * field. The climate biome decides <em>what</em> grows, so the same tree cover is spruce in the
 * taiga and jungle on the equator. And the column's surroundings decide the details that make a
 * place look real: reeds and willows on the river bank, palms on a warm beach, a flower meadow
 * that is one species here and another a hundred blocks on, a wheat field that is all wheat.
 *
 * <p>Spatial variety comes from {@link CellNoise}, a seedless hash of the block coordinates, so the
 * same data and config draw the same meadows on every server.
 *
 * <p>Every probability is per column and already scaled to look right at vanilla's forest density:
 * around a tree per sixteen columns in closed forest, a tree per two hundred on a steppe.
 */
public final class VegetationPlan {

    private static final long MEADOW_SALT = 0x4D454144L;
    private static final long SPECIES_SALT = 0x53504543L;
    private static final long GROVE_SALT = 0x47524F56L;
    private static final long SUNFLOWER_SALT = 0x53554E46L;
    private static final long BAMBOO_SALT = 0x42414D42L;
    private static final long FIELD_SALT = 0x4649454CL;
    private static final long CORAL_SALT = 0x434F5241L;
    private static final long KELP_SALT = 0x4B454C50L;

    /** Edge of one crop field, in blocks: one crop, one orientation, one ripeness per field. */
    static final int FIELD_SIZE = 16;
    /** Every this-many rows of a field is an irrigation channel. */
    static final int CHANNEL_EVERY = 8;

    private final double density;
    private final boolean customTrees;
    private final boolean farmland;

    /**
     * @param density     multiplier on every natural probability; {@code 1.0} is the calibrated density
     * @param customTrees draw TerraForge's procedural trees as well as vanilla's
     * @param farmland    till flat cropland into fields
     */
    public VegetationPlan(double density, boolean customTrees, boolean farmland) {
        if (!(density >= 0.0) || !Double.isFinite(density)) {
            throw new IllegalArgumentException("vegetation density must not be negative: " + density);
        }
        this.density = density;
        this.customTrees = customTrees;
        this.farmland = farmland;
    }

    /** Calibrated density, every feature on. */
    public VegetationPlan(double density) {
        this(density, true, true);
    }

    public double density() {
        return density;
    }

    /** What to place on a column, and where relative to its surface block. */
    public sealed interface Placement {

        /** Nothing grows here. */
        record None() implements Placement {
            static final None INSTANCE = new None();
        }

        /** A single block on top of the surface: grass, a flower, a carpet, a pumpkin, a mushroom. */
        record Plant(Material material) implements Placement {
        }

        /** A two-block plant on top of the surface: tall grass, a large fern, a sunflower, a peony. */
        record TallPlant(Material material) implements Placement {
        }

        /** A cactus of the given height on sand, flowering or not. */
        record Cactus(int height, boolean flower) implements Placement {
        }

        /** Sugar cane of the given height, beside water. */
        record SugarCane(int height) implements Placement {
        }

        /** A bamboo stalk of the given height. */
        record Bamboo(int height) implements Placement {
        }

        /** A vanilla tree grown from the block above the surface. */
        record Tree(TreeType type) implements Placement {
        }

        /** A TerraForge tree, drawn block by block from the surface. */
        record CustomTree(TreeBlueprint blueprint) implements Placement {
        }

        /**
         * Tilled ground with a crop on it. {@code crop} is {@code null} for bare farmland; the stem
         * blocks of pumpkins and melons carry their own age.
         */
        record Crop(Material crop, int age) implements Placement {
        }

        /** The surface block replaced by still water: an irrigation channel through a field. */
        record Irrigation() implements Placement {
            static final Irrigation INSTANCE = new Irrigation();
        }

        /** A lily pad on the water's surface. */
        record LilyPad() implements Placement {
            static final LilyPad INSTANCE = new LilyPad();
        }

        /** Seagrass on the bed, short or tall. */
        record Seagrass(boolean tall) implements Placement {
        }

        /** A kelp stalk of the given height rising from the bed. */
        record Kelp(int height) implements Placement {
        }

        /** A coral block on the bed with, optionally, a coral or fan on top of it. */
        record Coral(Material block, Material top) implements Placement {
        }

        /** A cluster of sea pickles on the bed. */
        record SeaPickles(int count) implements Placement {
        }
    }

    /**
     * @param column the column and its surroundings
     * @param random draws for this column; consumed deterministically in a fixed order
     */
    public Placement choose(Column column, Random random) {
        TerrainSample sample = column.sample();
        if (sample.isWater() || sample.biome().isWater()) {
            return chooseUnderwater(column, random);
        }
        if (density == 0.0 && !farmland) {
            return Placement.None.INSTANCE;
        }
        ClimateBiome biome = sample.biome();
        LandcoverClass cover = sample.landcover();
        Material ground = column.ground();

        if (farmland && cover == LandcoverClass.CROPLAND && column.flat() && arable(biome, ground)) {
            Placement field = field(column, random);
            if (field != null) {
                return field;
            }
        }
        if (density == 0.0) {
            return Placement.None.INSTANCE;
        }

        // Reeds first: they need water beside them, and that is rarer than any other condition.
        if (column.freshWaterAtLevel() && growsReeds(biome, ground)
                && random.nextDouble() < reedChance(biome) * density) {
            return new Placement.SugarCane(2 + random.nextInt(3));
        }

        double treeChance = treeChance(biome, cover) * density;
        if (column.riparian()) {
            treeChance += riparianTreeBonus(biome) * density;
        }
        if (biome == ClimateBiome.BEACH && column.warmNeighbourhood()) {
            treeChance += 0.012 * density;
        }
        if (random.nextDouble() < treeChance && growsTrees(biome, ground)) {
            Placement tree = tree(column, random);
            if (tree != null) {
                return tree;
            }
        }

        double plantChance = plantChance(biome, cover) * density;
        if (column.riparian()) {
            plantChance = Math.max(plantChance, 0.45 * density);
        }
        if (random.nextDouble() >= plantChance) {
            return Placement.None.INSTANCE;
        }
        return plant(column, random);
    }

    // ---------------------------------------------------------------------------------------------
    // How much

    /** Per-column tree probability: land cover sets the amount, the biome only fills the gaps. */
    static double treeChance(ClimateBiome biome, LandcoverClass cover) {
        return switch (cover) {
            case TREE_COVER -> 0.06;
            case MANGROVES -> 0.08;
            case SHRUBLAND -> 0.012;
            case HERBACEOUS_WETLAND -> 0.02;
            case GRASSLAND, CROPLAND, BUILT_UP -> 0.003;
            case BARE_SPARSE, SNOW_ICE, PERMANENT_WATER, MOSS_LICHEN -> 0.0;
            case UNKNOWN -> switch (biome) {
                case TEMPERATE_FOREST, TEMPERATE_RAINFOREST, BOREAL_FOREST,
                     TROPICAL_RAINFOREST, TROPICAL_SEASONAL_FOREST, MOUNTAIN_FOREST -> 0.05;
                case MANGROVE -> 0.08;
                case WETLAND -> 0.02;
                case SAVANNA -> 0.012;
                case SHRUBLAND -> 0.01;
                case GRASSLAND, MOUNTAIN_MEADOW -> 0.003;
                case SEMI_DESERT -> 0.001;
                default -> 0.0;
            };
        };
    }

    /** Extra tree probability on a river or lake bank: willows, swamp oaks, palms at an oasis. */
    static double riparianTreeBonus(ClimateBiome biome) {
        return switch (biome) {
            case TEMPERATE_FOREST, TEMPERATE_RAINFOREST, GRASSLAND, WETLAND, MOUNTAIN_MEADOW -> 0.04;
            case TROPICAL_RAINFOREST, TROPICAL_SEASONAL_FOREST, SAVANNA -> 0.03;
            case DESERT, SEMI_DESERT, SHRUBLAND -> 0.02;
            case BOREAL_FOREST, MOUNTAIN_FOREST -> 0.01;
            default -> 0.0;
        };
    }

    /** Per-column chance of a ground plant, given that no tree was placed. */
    static double plantChance(ClimateBiome biome, LandcoverClass cover) {
        double byBiome = switch (biome) {
            case GRASSLAND, SAVANNA, MOUNTAIN_MEADOW -> 0.40;
            case WETLAND -> 0.35;
            case TEMPERATE_FOREST, TEMPERATE_RAINFOREST, TROPICAL_RAINFOREST,
                 TROPICAL_SEASONAL_FOREST, MOUNTAIN_FOREST -> 0.25;
            case BOREAL_FOREST -> 0.18;
            case SHRUBLAND -> 0.20;
            case SEMI_DESERT -> 0.06;
            case DESERT -> 0.03;
            case MANGROVE -> 0.12;
            case TUNDRA -> 0.04;
            case BEACH -> 0.02;
            default -> 0.0;
        };
        return switch (cover) {
            case BARE_SPARSE, SNOW_ICE, PERMANENT_WATER -> 0.0;
            case GRASSLAND, CROPLAND -> Math.max(byBiome, 0.35);
            case HERBACEOUS_WETLAND -> Math.max(byBiome, 0.30);
            default -> byBiome;
        };
    }

    static double reedChance(ClimateBiome biome) {
        return switch (biome) {
            case WETLAND, MANGROVE -> 0.45;
            case TROPICAL_RAINFOREST, TROPICAL_SEASONAL_FOREST, SAVANNA -> 0.40;
            case DESERT, SEMI_DESERT -> 0.35; // the oasis
            case TEMPERATE_FOREST, TEMPERATE_RAINFOREST, GRASSLAND, SHRUBLAND, BEACH -> 0.30;
            case BOREAL_FOREST, MOUNTAIN_FOREST, MOUNTAIN_MEADOW -> 0.12;
            default -> 0.0;
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Trees

    /** Which tree to grow; {@code null} for a biome that grows none on this ground. */
    private Placement tree(Column column, Random random) {
        ClimateBiome biome = column.sample().biome();
        double pick = random.nextDouble();
        double grove = CellNoise.cell(column.x(), column.z(), 48, GROVE_SALT);

        if (column.riparian() && customTrees) {
            switch (biome) {
                case TEMPERATE_FOREST, TEMPERATE_RAINFOREST, GRASSLAND, WETLAND, MOUNTAIN_MEADOW -> {
                    if (pick < 0.6) {
                        return custom(Shape.WILLOW, Wood.OAK, random);
                    }
                }
                case DESERT, SEMI_DESERT -> {
                    return custom(Shape.PALM, Wood.JUNGLE, random);
                }
                default -> { }
            }
        }
        if (biome == ClimateBiome.BEACH) {
            return column.warmNeighbourhood() && customTrees ? custom(Shape.PALM, Wood.JUNGLE, random) : null;
        }

        return switch (biome) {
            case BOREAL_FOREST -> {
                if (customTrees && pick < 0.25) {
                    yield custom(Shape.TALL_PINE, Wood.SPRUCE, random);
                }
                if (customTrees && pick < 0.29) {
                    yield custom(Shape.FALLEN_LOG, Wood.SPRUCE, random);
                }
                yield pick < 0.6 ? vanilla(TreeType.REDWOOD) : pick < 0.92 ? vanilla(TreeType.TALL_REDWOOD)
                        : vanilla(TreeType.MEGA_PINE);
            }
            case MOUNTAIN_FOREST -> {
                if (customTrees && pick < 0.15) {
                    yield custom(Shape.TALL_PINE, Wood.SPRUCE, random);
                }
                yield pick < 0.5 ? vanilla(TreeType.REDWOOD) : pick < 0.75 ? vanilla(TreeType.TALL_REDWOOD)
                        : pick < 0.9 ? vanilla(TreeType.TREE) : vanilla(TreeType.MEGA_REDWOOD);
            }
            case TEMPERATE_FOREST -> {
                if (grove < 0.15) { // a birch grove
                    yield pick < 0.6 ? vanilla(TreeType.BIRCH) : vanilla(TreeType.TALL_BIRCH);
                }
                if (grove < 0.20) { // a cherry grove
                    yield pick < 0.8 ? vanilla(TreeType.CHERRY) : vanilla(TreeType.TREE);
                }
                if (customTrees && pick < 0.04) {
                    yield custom(Shape.FALLEN_LOG, Wood.OAK, random);
                }
                yield pick < 0.55 ? vanilla(TreeType.TREE) : pick < 0.8 ? vanilla(TreeType.BIRCH)
                        : vanilla(TreeType.BIG_TREE);
            }
            case TEMPERATE_RAINFOREST -> {
                if (customTrees && pick < 0.05) {
                    yield custom(Shape.FALLEN_LOG, Wood.DARK_OAK, random);
                }
                yield pick < 0.45 ? vanilla(TreeType.BIG_TREE) : pick < 0.75 ? vanilla(TreeType.DARK_OAK)
                        : pick < 0.9 ? vanilla(TreeType.TREE) : vanilla(TreeType.AZALEA);
            }
            case TROPICAL_RAINFOREST -> {
                if (customTrees && pick < 0.03) {
                    yield custom(Shape.FALLEN_LOG, Wood.JUNGLE, random);
                }
                yield pick < 0.45 ? vanilla(TreeType.JUNGLE) : pick < 0.8 ? vanilla(TreeType.SMALL_JUNGLE)
                        : pick < 0.9 ? vanilla(TreeType.COCOA_TREE) : vanilla(TreeType.JUNGLE_BUSH);
            }
            case TROPICAL_SEASONAL_FOREST -> pick < 0.55 ? vanilla(TreeType.SMALL_JUNGLE)
                    : pick < 0.8 ? vanilla(TreeType.TREE) : vanilla(TreeType.JUNGLE_BUSH);
            case SAVANNA -> {
                if (customTrees && pick < 0.06) {
                    yield custom(Shape.BAOBAB, Wood.ACACIA, random);
                }
                if (customTrees && pick < 0.3) {
                    yield custom(Shape.SHRUB, Wood.ACACIA, random);
                }
                yield vanilla(TreeType.ACACIA);
            }
            case SHRUBLAND -> {
                if (customTrees && pick < 0.15) {
                    yield custom(Shape.CYPRESS, Wood.SPRUCE, random);
                }
                if (customTrees && pick < 0.3) {
                    yield custom(Shape.DEAD_TREE, Wood.OAK, random);
                }
                yield customTrees && pick < 0.7 ? custom(Shape.SHRUB, Wood.OAK, random) : vanilla(TreeType.JUNGLE_BUSH);
            }
            case SEMI_DESERT -> customTrees ? custom(Shape.DEAD_TREE, Wood.OAK, random) : null;
            case WETLAND -> customTrees && pick < 0.35 ? custom(Shape.WILLOW, Wood.OAK, random) : vanilla(TreeType.SWAMP);
            case MANGROVE -> pick < 0.7 ? vanilla(TreeType.MANGROVE) : vanilla(TreeType.TALL_MANGROVE);
            case GRASSLAND -> pick < 0.75 ? vanilla(TreeType.TREE) : pick < 0.9 ? vanilla(TreeType.BIRCH)
                    : vanilla(TreeType.BIG_TREE);
            case MOUNTAIN_MEADOW -> pick < 0.8 ? vanilla(TreeType.REDWOOD) : vanilla(TreeType.TREE);
            default -> null;
        };
    }

    private static Placement vanilla(TreeType type) {
        return new Placement.Tree(type);
    }

    private static Placement custom(Shape shape, Wood wood, Random random) {
        return new Placement.CustomTree(TreeShapes.build(shape, wood, random));
    }

    // ---------------------------------------------------------------------------------------------
    // Ground plants

    private Placement plant(Column column, Random random) {
        ClimateBiome biome = column.sample().biome();
        Material ground = column.ground();
        int x = column.x();
        int z = column.z();
        double pick = random.nextDouble();

        switch (biome) {
            case DESERT -> {
                if (ground != Material.SAND) {
                    return Placement.None.INSTANCE;
                }
                if (pick < 0.15) {
                    return new Placement.Cactus(1 + random.nextInt(3), random.nextDouble() < 0.15);
                }
                if (column.riparian()) {
                    return pick < 0.6 ? new Placement.Plant(Material.TALL_DRY_GRASS)
                            : new Placement.Plant(Material.SHORT_DRY_GRASS);
                }
                return pick < 0.7 ? new Placement.Plant(Material.DEAD_BUSH)
                        : new Placement.Plant(Material.SHORT_DRY_GRASS);
            }
            case SEMI_DESERT -> {
                if (ground == Material.SAND && pick < 0.1) {
                    return new Placement.Cactus(1 + random.nextInt(2), random.nextDouble() < 0.1);
                }
                if (ground != Material.SAND && ground != Material.TERRACOTTA && ground != Material.GRASS_BLOCK) {
                    return Placement.None.INSTANCE;
                }
                return pick < 0.5 ? new Placement.Plant(Material.DEAD_BUSH)
                        : pick < 0.85 ? new Placement.Plant(Material.SHORT_DRY_GRASS)
                        : new Placement.Plant(Material.TALL_DRY_GRASS);
            }
            case BEACH -> {
                if (ground != Material.SAND) {
                    return Placement.None.INSTANCE;
                }
                return pick < 0.7 ? new Placement.Plant(Material.SHORT_DRY_GRASS)
                        : new Placement.Plant(Material.DEAD_BUSH);
            }
            case TUNDRA, ALPINE, GLACIER, BARE_ROCK, STONY_SHORE -> {
                return Placement.None.INSTANCE; // snow, ice and rock: nothing takes root
            }
            default -> { }
        }
        if (!growsPlants(ground)) {
            return Placement.None.INSTANCE;
        }

        // The riparian strip is lush whatever the biome: tall grass, ferns, orchids, dripleaf.
        if (column.riparian() && pick < 0.5) {
            return riparianPlant(biome, random);
        }

        return switch (biome) {
            case GRASSLAND, MOUNTAIN_MEADOW -> meadowPlant(column, biome, random);
            case SAVANNA -> pick < 0.45 ? new Placement.Plant(Material.SHORT_DRY_GRASS)
                    : pick < 0.7 ? new Placement.Plant(Material.TALL_DRY_GRASS)
                    : pick < 0.9 ? new Placement.Plant(Material.SHORT_GRASS)
                    : pick < 0.96 ? new Placement.Plant(Material.BUSH)
                    : new Placement.Plant(Material.DEAD_BUSH);
            case SHRUBLAND -> pick < 0.35 ? new Placement.Plant(Material.SHORT_DRY_GRASS)
                    : pick < 0.55 ? new Placement.Plant(Material.BUSH)
                    : pick < 0.7 ? new Placement.Plant(Material.DEAD_BUSH)
                    : pick < 0.85 ? new Placement.Plant(Material.SHORT_GRASS)
                    : pick < 0.93 ? new Placement.Plant(Material.WILDFLOWERS)
                    : new Placement.Plant(Material.TALL_DRY_GRASS);
            case TEMPERATE_FOREST -> {
                if (CellNoise.cell(x, z, 48, GROVE_SALT) < 0.20 && CellNoise.cell(x, z, 48, GROVE_SALT) >= 0.15
                        && pick < 0.3) {
                    yield new Placement.Plant(Material.PINK_PETALS); // under the cherry grove
                }
                yield pick < 0.45 ? new Placement.Plant(Material.SHORT_GRASS)
                        : pick < 0.6 ? new Placement.Plant(Material.FERN)
                        : pick < 0.7 ? new Placement.Plant(Material.BUSH)
                        : pick < 0.8 ? new Placement.Plant(Material.LEAF_LITTER)
                        : pick < 0.86 ? new Placement.TallPlant(Material.TALL_GRASS)
                        : pick < 0.93 ? new Placement.Plant(flower(column, biome, random))
                        : pick < 0.96 ? new Placement.TallPlant(random.nextBoolean() ? Material.LILAC : Material.PEONY)
                        : pick < 0.98 ? new Placement.TallPlant(Material.ROSE_BUSH)
                        : new Placement.Plant(random.nextBoolean() ? Material.RED_MUSHROOM : Material.BROWN_MUSHROOM);
            }
            case TEMPERATE_RAINFOREST -> pick < 0.3 ? new Placement.Plant(Material.FERN)
                    : pick < 0.45 ? new Placement.TallPlant(Material.LARGE_FERN)
                    : pick < 0.6 ? new Placement.Plant(Material.MOSS_CARPET)
                    : pick < 0.75 ? new Placement.Plant(Material.SHORT_GRASS)
                    : pick < 0.85 ? new Placement.Plant(Material.LEAF_LITTER)
                    : pick < 0.93 ? new Placement.Plant(random.nextBoolean() ? Material.RED_MUSHROOM : Material.BROWN_MUSHROOM)
                    : pick < 0.97 ? new Placement.Plant(Material.BUSH)
                    : new Placement.Plant(Material.LILY_OF_THE_VALLEY);
            case BOREAL_FOREST, MOUNTAIN_FOREST -> pick < 0.4 ? new Placement.Plant(Material.FERN)
                    : pick < 0.55 ? new Placement.TallPlant(Material.LARGE_FERN)
                    : pick < 0.75 ? new Placement.Plant(Material.SHORT_GRASS)
                    : pick < 0.85 ? new Placement.Plant(Material.SWEET_BERRY_BUSH)
                    : pick < 0.92 ? new Placement.Plant(Material.MOSS_CARPET)
                    : pick < 0.97 ? new Placement.Plant(random.nextBoolean() ? Material.RED_MUSHROOM : Material.BROWN_MUSHROOM)
                    : new Placement.Plant(Material.LEAF_LITTER);
            case TROPICAL_RAINFOREST -> {
                if (CellNoise.cell(x, z, 24, BAMBOO_SALT) < 0.10 && pick < 0.5) {
                    yield new Placement.Bamboo(4 + random.nextInt(9)); // a bamboo grove
                }
                yield pick < 0.3 ? new Placement.Plant(Material.FERN)
                        : pick < 0.45 ? new Placement.TallPlant(Material.LARGE_FERN)
                        : pick < 0.6 ? new Placement.Plant(Material.SHORT_GRASS)
                        : pick < 0.72 ? new Placement.Plant(Material.MOSS_CARPET)
                        : pick < 0.8 ? new Placement.TallPlant(Material.TALL_GRASS)
                        : pick < 0.86 ? new Placement.Plant(Material.BUSH)
                        : pick < 0.9 ? new Placement.Plant(Material.MELON)
                        : pick < 0.95 ? new Placement.Plant(Material.BLUE_ORCHID)
                        : pick < 0.98 ? new Placement.Plant(Material.BROWN_MUSHROOM)
                        : new Placement.Plant(Material.TORCHFLOWER);
            }
            case TROPICAL_SEASONAL_FOREST -> pick < 0.35 ? new Placement.Plant(Material.SHORT_GRASS)
                    : pick < 0.55 ? new Placement.TallPlant(Material.TALL_GRASS)
                    : pick < 0.7 ? new Placement.Plant(Material.FERN)
                    : pick < 0.8 ? new Placement.Plant(Material.SHORT_DRY_GRASS)
                    : pick < 0.9 ? new Placement.Plant(Material.BUSH)
                    : pick < 0.96 ? new Placement.Plant(Material.LEAF_LITTER)
                    : new Placement.Plant(Material.WILDFLOWERS);
            case WETLAND -> pick < 0.3 ? new Placement.TallPlant(Material.TALL_GRASS)
                    : pick < 0.5 ? new Placement.Plant(Material.SHORT_GRASS)
                    : pick < 0.62 ? new Placement.Plant(Material.FERN)
                    : pick < 0.72 ? new Placement.Plant(Material.BLUE_ORCHID)
                    : pick < 0.8 ? new Placement.Plant(Material.MOSS_CARPET)
                    : pick < 0.88 ? new Placement.Plant(Material.FIREFLY_BUSH)
                    : pick < 0.94 ? new Placement.Plant(Material.BROWN_MUSHROOM)
                    : new Placement.TallPlant(Material.LARGE_FERN);
            case MANGROVE -> pick < 0.5 ? new Placement.Plant(Material.MOSS_CARPET)
                    : pick < 0.8 ? new Placement.Plant(Material.MANGROVE_PROPAGULE)
                    : new Placement.Plant(Material.SHORT_GRASS);
            default -> new Placement.Plant(Material.SHORT_GRASS);
        };
    }

    /** The lush strip along fresh water. */
    private static Placement riparianPlant(ClimateBiome biome, Random random) {
        double pick = random.nextDouble();
        boolean warm = switch (biome) {
            case TROPICAL_RAINFOREST, TROPICAL_SEASONAL_FOREST, SAVANNA, MANGROVE, WETLAND -> true;
            default -> false;
        };
        if (warm) {
            return pick < 0.3 ? new Placement.TallPlant(Material.TALL_GRASS)
                    : pick < 0.5 ? new Placement.Plant(Material.FIREFLY_BUSH)
                    : pick < 0.65 ? new Placement.Plant(Material.BLUE_ORCHID)
                    : pick < 0.8 ? new Placement.Plant(Material.SMALL_DRIPLEAF)
                    : pick < 0.9 ? new Placement.TallPlant(Material.LARGE_FERN)
                    : new Placement.Plant(Material.FERN);
        }
        return pick < 0.4 ? new Placement.TallPlant(Material.TALL_GRASS)
                : pick < 0.6 ? new Placement.TallPlant(Material.LARGE_FERN)
                : pick < 0.75 ? new Placement.Plant(Material.FERN)
                : pick < 0.85 ? new Placement.Plant(Material.BLUE_ORCHID)
                : pick < 0.92 ? new Placement.Plant(Material.FIREFLY_BUSH)
                : new Placement.Plant(Material.SHORT_GRASS);
    }

    /** Grassland: grass, with flower meadows and the odd sunflower field where the noise says so. */
    private static Placement meadowPlant(Column column, ClimateBiome biome, Random random) {
        int x = column.x();
        int z = column.z();
        double pick = random.nextDouble();
        if (biome == ClimateBiome.GRASSLAND && CellNoise.cell(x, z, 20, SUNFLOWER_SALT) < 0.05) {
            return pick < 0.5 ? new Placement.TallPlant(Material.SUNFLOWER)
                    : new Placement.Plant(Material.SHORT_GRASS);
        }
        double meadow = CellNoise.smooth(x, z, 24, MEADOW_SALT);
        double flowerShare = meadow > 0.6 ? 0.45 : meadow > 0.45 ? 0.15 : 0.04;
        if (pick < flowerShare) {
            return new Placement.Plant(flower(column, biome, random));
        }
        if (pick < flowerShare + 0.12) {
            return new Placement.TallPlant(Material.TALL_GRASS);
        }
        if (pick < flowerShare + 0.15) {
            return new Placement.Plant(Material.BUSH);
        }
        return new Placement.Plant(Material.SHORT_GRASS);
    }

    /**
     * The flower for a column: mostly one species per twelve-block patch, so meadows come in drifts
     * of colour, with a third of the columns drawn freely so the drifts are never pure.
     */
    static Material flower(Column column, ClimateBiome biome, Random random) {
        Material[] palette = biome == ClimateBiome.MOUNTAIN_MEADOW ? ALPINE_FLOWERS
                : biome == ClimateBiome.TEMPERATE_FOREST ? WOODLAND_FLOWERS : MEADOW_FLOWERS;
        if (random.nextDouble() < 0.33) {
            return palette[random.nextInt(palette.length)];
        }
        long patch = CellNoise.cellId(column.x(), column.z(), 12, SPECIES_SALT);
        return palette[Math.floorMod(patch, palette.length)];
    }

    private static final Material[] MEADOW_FLOWERS = {
        Material.DANDELION, Material.POPPY, Material.OXEYE_DAISY, Material.CORNFLOWER, Material.AZURE_BLUET,
        Material.RED_TULIP, Material.ORANGE_TULIP, Material.WHITE_TULIP, Material.PINK_TULIP,
        Material.ALLIUM, Material.WILDFLOWERS,
    };
    private static final Material[] WOODLAND_FLOWERS = {
        Material.LILY_OF_THE_VALLEY, Material.AZURE_BLUET, Material.OXEYE_DAISY, Material.ALLIUM,
        Material.DANDELION, Material.POPPY,
    };
    private static final Material[] ALPINE_FLOWERS = {
        Material.CORNFLOWER, Material.AZURE_BLUET, Material.OXEYE_DAISY, Material.ALLIUM, Material.WILDFLOWERS,
    };

    // ---------------------------------------------------------------------------------------------
    // Fields

    /** Ground that can be tilled, in a climate that farms. */
    static boolean arable(ClimateBiome biome, Material ground) {
        if (ground != Material.GRASS_BLOCK && ground != Material.DIRT && ground != Material.PODZOL) {
            return false;
        }
        return switch (biome) {
            case GRASSLAND, SAVANNA, TEMPERATE_FOREST, TEMPERATE_RAINFOREST, TROPICAL_SEASONAL_FOREST,
                 TROPICAL_RAINFOREST, SHRUBLAND, MOUNTAIN_MEADOW, WETLAND, BOREAL_FOREST, MOUNTAIN_FOREST -> true;
            default -> false;
        };
    }

    /**
     * One column of a crop field, or {@code null} when this field lies fallow and the column
     * should grow as meadow.
     *
     * <p>Every {@link #FIELD_SIZE}-block cell is one field: one crop, one row direction, one
     * ripeness. Every {@link #CHANNEL_EVERY}th row is an irrigation channel, which is what keeps
     * the farmland wet.
     */
    private Placement field(Column column, Random random) {
        int x = column.x();
        int z = column.z();
        long id = CellNoise.cellId(x, z, FIELD_SIZE, FIELD_SALT);
        double kind = CellNoise.cell(x, z, FIELD_SIZE, FIELD_SALT);
        if (kind < 0.12) {
            return null; // fallow
        }
        boolean rowsAlongX = (id & 1) == 0;
        int across = rowsAlongX ? Math.floorMod(z, FIELD_SIZE) : Math.floorMod(x, FIELD_SIZE);
        if (across % CHANNEL_EVERY == CHANNEL_EVERY / 2) {
            return Placement.Irrigation.INSTANCE;
        }
        boolean warm = switch (column.sample().biome()) {
            case SAVANNA, TROPICAL_RAINFOREST, TROPICAL_SEASONAL_FOREST -> true;
            default -> false;
        };
        int ripeness = 3 + (int) ((id >>> 8) & 3) + (int) ((id >>> 10) & 1); // 3..7
        double roll = random.nextDouble();
        if (kind < 0.20) { // pumpkins, or melons in the warm south
            Material fruit = warm && kind < 0.16 ? Material.MELON : Material.PUMPKIN;
            Material stem = fruit == Material.MELON ? Material.MELON_STEM : Material.PUMPKIN_STEM;
            if (roll < 0.5) {
                return new Placement.Crop(stem, Math.min(7, ripeness + random.nextInt(2)));
            }
            return roll < 0.72 ? new Placement.Plant(fruit) : new Placement.Crop(null, 0);
        }
        Material crop = kind < 0.55 ? Material.WHEAT
                : kind < 0.72 ? Material.POTATOES
                : kind < 0.88 ? Material.CARROTS
                : Material.BEETROOTS;
        int maxAge = crop == Material.BEETROOTS ? 3 : 7;
        int age = Math.min(maxAge, Math.max(0, (crop == Material.BEETROOTS ? ripeness / 2 : ripeness)
                + random.nextInt(3) - 1));
        if (roll < 0.92) {
            return new Placement.Crop(crop, age);
        }
        return new Placement.Crop(null, 0); // a gap in the row
    }

    // ---------------------------------------------------------------------------------------------
    // Under water

    /** Lily pads on still fresh water; seagrass, kelp, coral and sea pickles on the sea bed. */
    private Placement chooseUnderwater(Column column, Random random) {
        if (density == 0.0) {
            return Placement.None.INSTANCE;
        }
        TerrainSample sample = column.sample();
        int depth = sample.waterSurfaceY() - sample.surfaceY();
        if (depth < 1) {
            return Placement.None.INSTANCE;
        }
        ClimateBiome biome = sample.biome();
        double roll = random.nextDouble();
        switch (sample.waterType()) {
            case LAKE, RIVER -> {
                if (biome == ClimateBiome.FROZEN_OCEAN) {
                    return Placement.None.INSTANCE; // a frozen lake
                }
                if (depth <= 3 && roll < 0.07 * density) {
                    return Placement.LilyPad.INSTANCE;
                }
                if (depth >= 2 && roll < 0.22 * density) {
                    return new Placement.Seagrass(depth >= 3 && random.nextDouble() < 0.3);
                }
                return Placement.None.INSTANCE;
            }
            case OCEAN -> {
                return switch (biome) {
                    case WARM_OCEAN -> warmSeabed(column, depth, roll, random);
                    case OCEAN -> temperateSeabed(column, depth, roll, random);
                    case DEEP_OCEAN -> depth >= 2 && roll < 0.04 * density
                            ? new Placement.Seagrass(false) : Placement.None.INSTANCE;
                    default -> Placement.None.INSTANCE; // the frozen ocean floor is bare
                };
            }
            case NONE -> {
                return Placement.None.INSTANCE;
            }
        }
        return Placement.None.INSTANCE;
    }

    private Placement warmSeabed(Column column, int depth, double roll, Random random) {
        double reef = CellNoise.smooth(column.x(), column.z(), 32, CORAL_SALT);
        if (depth <= 14 && reef > 0.62) {
            if (roll < 0.35 * density) {
                Material block = CORAL_BLOCKS[random.nextInt(CORAL_BLOCKS.length)];
                double top = random.nextDouble();
                Material crown = top < 0.4 ? CORAL_FANS[random.nextInt(CORAL_FANS.length)]
                        : top < 0.7 ? CORALS[random.nextInt(CORALS.length)] : null;
                return new Placement.Coral(block, crown);
            }
            if (roll < 0.45 * density) {
                return new Placement.SeaPickles(1 + random.nextInt(4));
            }
            if (roll < 0.65 * density) {
                return new Placement.Seagrass(random.nextDouble() < 0.4);
            }
            return Placement.None.INSTANCE;
        }
        if (depth >= 2 && roll < 0.18 * density) {
            return new Placement.Seagrass(depth >= 3 && random.nextDouble() < 0.35);
        }
        return Placement.None.INSTANCE;
    }

    private Placement temperateSeabed(Column column, int depth, double roll, Random random) {
        double forest = CellNoise.smooth(column.x(), column.z(), 40, KELP_SALT);
        if (depth >= 5 && forest > 0.55 && roll < 0.30 * density) {
            return new Placement.Kelp(Math.min(depth - 1, 4 + random.nextInt(Math.max(1, depth - 4))));
        }
        if (depth >= 2 && roll < 0.14 * density) {
            return new Placement.Seagrass(depth >= 3 && random.nextDouble() < 0.4);
        }
        return Placement.None.INSTANCE;
    }

    private static final Material[] CORAL_BLOCKS = {
        Material.TUBE_CORAL_BLOCK, Material.BRAIN_CORAL_BLOCK, Material.BUBBLE_CORAL_BLOCK,
        Material.FIRE_CORAL_BLOCK, Material.HORN_CORAL_BLOCK,
    };
    private static final Material[] CORALS = {
        Material.TUBE_CORAL, Material.BRAIN_CORAL, Material.BUBBLE_CORAL, Material.FIRE_CORAL, Material.HORN_CORAL,
    };
    private static final Material[] CORAL_FANS = {
        Material.TUBE_CORAL_FAN, Material.BRAIN_CORAL_FAN, Material.BUBBLE_CORAL_FAN,
        Material.FIRE_CORAL_FAN, Material.HORN_CORAL_FAN,
    };

    // ---------------------------------------------------------------------------------------------
    // Ground rules

    /** Ground a tree feature accepts as its base, in a biome that grows any. */
    static boolean growsTrees(ClimateBiome biome, Material ground) {
        if (biome == ClimateBiome.BEACH || biome == ClimateBiome.DESERT || biome == ClimateBiome.SEMI_DESERT) {
            return ground == Material.SAND || ground == Material.TERRACOTTA || growsTrees(ground);
        }
        return growsTrees(ground);
    }

    static boolean growsTrees(Material ground) {
        return ground == Material.GRASS_BLOCK || ground == Material.DIRT || ground == Material.PODZOL
                || ground == Material.MUD || ground == Material.COARSE_DIRT || ground == Material.ROOTED_DIRT;
    }

    /** Ground a grass or flower block survives on. */
    static boolean growsPlants(Material ground) {
        return ground == Material.GRASS_BLOCK || ground == Material.DIRT || ground == Material.PODZOL
                || ground == Material.MUD || ground == Material.COARSE_DIRT;
    }

    /** Ground sugar cane accepts, in a biome warm enough for it. */
    static boolean growsReeds(ClimateBiome biome, Material ground) {
        return reedChance(biome) > 0.0 && (ground == Material.SAND || growsPlants(ground));
    }
}
