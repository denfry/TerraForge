package dev.terraforge.generator.vegetation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.generator.vegetation.TreeShapes.Shape;
import dev.terraforge.generator.vegetation.VegetationPlan.Placement;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.junit.jupiter.api.Test;

/**
 * Calibration of the vegetation pass, measured over many columns: the biomes that looked empty in
 * the audited world grow something, the ones that should be empty stay so, and the features that
 * make a place recognisable -- reeds on the bank, fields on the cropland, palms on a warm beach,
 * a reef under a warm sea -- appear where they should and nowhere else.
 */
class VegetationPlanTest {

    private static final int COLUMNS = 20_000;

    @Test
    void temperateTreeCoverIsAForestOfOakAndBirch() {
        Tally tally = tally(ClimateBiome.TEMPERATE_FOREST, LandcoverClass.TREE_COVER, Material.GRASS_BLOCK, 1.0);

        assertThat(tally.trees).isBetween(COLUMNS / 25, COLUMNS / 12);
        assertThat(tally.treeTypes).contains(TreeType.TREE, TreeType.BIRCH, TreeType.BIG_TREE);
        assertThat(tally.treeTypes).doesNotContain(TreeType.REDWOOD, TreeType.JUNGLE, TreeType.ACACIA);
        assertThat(tally.customShapes()).contains(Shape.FALLEN_LOG); // the forest floor
        assertThat(tally.plants).isGreaterThan(COLUMNS / 10);
        assertThat(tally.plantMaterials).contains(Material.FERN, Material.BUSH, Material.LEAF_LITTER, Material.LILAC);
    }

    @Test
    void borealTreeCoverGrowsSpruceAndTallPines() {
        Tally tally = tally(ClimateBiome.BOREAL_FOREST, LandcoverClass.TREE_COVER, Material.PODZOL, 1.0);

        assertThat(tally.trees).isGreaterThan(COLUMNS / 25);
        assertThat(tally.treeTypes).containsOnly(TreeType.REDWOOD, TreeType.TALL_REDWOOD, TreeType.MEGA_PINE);
        assertThat(tally.customShapes()).contains(Shape.TALL_PINE);
        assertThat(tally.plantMaterials).contains(Material.FERN, Material.SWEET_BERRY_BUSH, Material.LARGE_FERN);
    }

    @Test
    void grasslandIsMeadowWithFlowerDriftsAndTheOddTree() {
        Tally tally = tally(ClimateBiome.GRASSLAND, LandcoverClass.GRASSLAND, Material.GRASS_BLOCK, 1.0);

        assertThat(tally.trees).isBetween(COLUMNS / 1000, COLUMNS / 100);
        assertThat(tally.plants).isBetween(COLUMNS / 4, COLUMNS / 2);
        assertThat(tally.plantMaterials).contains(Material.SHORT_GRASS, Material.TALL_GRASS, Material.DANDELION,
                Material.POPPY, Material.CORNFLOWER, Material.SUNFLOWER);
        // Flowers come in patches: over a span of ground the mix has many species, but any single
        // twelve-block patch is dominated by one of them.
        assertThat(tally.plantMaterials.stream().filter(VegetationPlanTest::isFlower).count()).isGreaterThan(5);
    }

    @Test
    void flowerDriftsAreOneSpeciesPerPatch() {
        VegetationPlan plan = new VegetationPlan(1.0);
        TerrainSample meadow = sample(ClimateBiome.GRASSLAND, LandcoverClass.GRASSLAND);
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        Random random = new Random(7);
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                for (int repeat = 0; repeat < 20; repeat++) {
                    Material flower = VegetationPlan.flower(Column.plain(meadow, Material.GRASS_BLOCK, x, z),
                            ClimateBiome.GRASSLAND, random);
                    counts.merge(flower, 1, Integer::sum);
                }
            }
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        int dominant = counts.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        assertThat(dominant).isGreaterThan(total / 2);
        assertThat(counts.size()).isGreaterThan(3); // but never a monoculture
    }

    @Test
    void unknownLandCoverFallsBackToTheBiome() {
        Tally tally = tally(ClimateBiome.TROPICAL_RAINFOREST, LandcoverClass.UNKNOWN, Material.GRASS_BLOCK, 1.0);

        assertThat(tally.trees).isGreaterThan(COLUMNS / 40);
        assertThat(tally.treeTypes).containsOnly(TreeType.JUNGLE, TreeType.SMALL_JUNGLE, TreeType.COCOA_TREE,
                TreeType.JUNGLE_BUSH);
        assertThat(tally.plantMaterials).contains(Material.FERN, Material.MOSS_CARPET, Material.BLUE_ORCHID);
    }

    @Test
    void rainforestHasBambooGroves() {
        VegetationPlan plan = new VegetationPlan(1.0);
        TerrainSample jungle = sample(ClimateBiome.TROPICAL_RAINFOREST, LandcoverClass.TREE_COVER);
        int bamboo = 0;
        Random random = new Random(3);
        for (int x = 0; x < 200; x++) {
            for (int z = 0; z < 200; z++) {
                if (plan.choose(Column.plain(jungle, Material.GRASS_BLOCK, x, z), random) instanceof Placement.Bamboo) {
                    bamboo++;
                }
            }
        }
        assertThat(bamboo).isBetween(200, 4_000); // groves, not a jungle-wide bamboo lawn
    }

    @Test
    void savannaGrowsAcaciaBaobabsAndDryGrass() {
        Tally tally = tally(ClimateBiome.SAVANNA, LandcoverClass.SHRUBLAND, Material.GRASS_BLOCK, 1.0);

        assertThat(tally.treeTypes).containsOnly(TreeType.ACACIA);
        assertThat(tally.customShapes()).contains(Shape.BAOBAB, Shape.SHRUB);
        assertThat(tally.plantMaterials).contains(Material.SHORT_DRY_GRASS, Material.TALL_DRY_GRASS);
    }

    @Test
    void desertGrowsCactiAndDeadBushesOnSandOnly() {
        Tally sand = tally(ClimateBiome.DESERT, LandcoverClass.BARE_SPARSE, Material.SAND, 1.0);
        assertThat(sand.trees).isZero();
        assertThat(sand.plants).isZero(); // bare/sparse cover grows nothing
        Tally unknown = tally(ClimateBiome.DESERT, LandcoverClass.UNKNOWN, Material.SAND, 1.0);
        assertThat(unknown.plantMaterials).containsOnly(Material.DEAD_BUSH, Material.SHORT_DRY_GRASS);
        assertThat(unknown.cacti).isGreaterThan(0);
        assertThat(unknown.plants + unknown.cacti).isLessThan(COLUMNS / 20);
        assertThat(tally(ClimateBiome.DESERT, LandcoverClass.UNKNOWN, Material.SANDSTONE, 1.0).anything()).isZero();
    }

    @Test
    void riverBanksGrowReedsWillowsAndLushGrass() {
        VegetationPlan plan = new VegetationPlan(1.0);
        TerrainSample meadow = sample(ClimateBiome.GRASSLAND, LandcoverClass.GRASSLAND);
        Tally bank = new Tally();
        Tally inland = new Tally();
        Random random = new Random(11);
        for (int i = 0; i < COLUMNS; i++) {
            bank.add(plan.choose(Column.plain(meadow, Material.GRASS_BLOCK, i, 0).nearFreshWater(1, true), random));
            inland.add(plan.choose(Column.plain(meadow, Material.GRASS_BLOCK, i, 0), random));
        }
        assertThat(bank.reeds).isBetween(COLUMNS / 5, COLUMNS / 2);
        assertThat(inland.reeds).isZero();
        assertThat(bank.customShapes()).contains(Shape.WILLOW);
        assertThat(inland.customShapes()).doesNotContain(Shape.WILLOW);
        assertThat(bank.plantMaterials).contains(Material.TALL_GRASS, Material.LARGE_FERN, Material.BLUE_ORCHID);
        assertThat(bank.trees).isGreaterThan(inland.trees * 3);
    }

    @Test
    void reedsNeedWaterAtTheirOwnLevel() {
        VegetationPlan plan = new VegetationPlan(1.0);
        TerrainSample meadow = sample(ClimateBiome.WETLAND, LandcoverClass.HERBACEOUS_WETLAND);
        Tally above = new Tally();
        Random random = new Random(5);
        for (int i = 0; i < COLUMNS; i++) {
            above.add(plan.choose(Column.plain(meadow, Material.GRASS_BLOCK, i, 0).nearFreshWater(1, false), random));
        }
        assertThat(above.reeds).isZero();
    }

    @Test
    void anOasisGrowsPalmsAndReedsInTheDesert() {
        VegetationPlan plan = new VegetationPlan(1.0);
        TerrainSample desert = sample(ClimateBiome.DESERT, LandcoverClass.BARE_SPARSE);
        Tally oasis = new Tally();
        Random random = new Random(9);
        for (int i = 0; i < COLUMNS; i++) {
            oasis.add(plan.choose(Column.plain(desert, Material.SAND, i, 0).nearFreshWater(1, true), random));
        }
        assertThat(oasis.reeds).isGreaterThan(COLUMNS / 10);
        assertThat(oasis.customShapes()).containsExactly(Shape.PALM);
    }

    @Test
    void beachesGrowPalmsOnlyNearWarmClimates() {
        VegetationPlan plan = new VegetationPlan(1.0);
        TerrainSample beach = sample(ClimateBiome.BEACH, LandcoverClass.UNKNOWN);
        Tally warm = new Tally();
        Tally cold = new Tally();
        Random random = new Random(13);
        for (int i = 0; i < COLUMNS; i++) {
            warm.add(plan.choose(Column.plain(beach, Material.SAND, i, 0).inWarmNeighbourhood(), random));
            cold.add(plan.choose(Column.plain(beach, Material.SAND, i, 0), random));
        }
        assertThat(warm.customShapes()).containsExactly(Shape.PALM);
        assertThat(warm.custom).isBetween(COLUMNS / 200, COLUMNS / 40);
        assertThat(cold.trees + cold.custom).isZero();
    }

    @Test
    void flatCroplandBecomesFieldsOfOneCropWithIrrigation() {
        VegetationPlan plan = new VegetationPlan(1.0);
        TerrainSample cropland = sample(ClimateBiome.GRASSLAND, LandcoverClass.CROPLAND);
        Random random = new Random(17);
        int crops = 0;
        int channels = 0;
        int meadow = 0;
        Set<Material> cropKinds = EnumSet.noneOf(Material.class);
        for (int fx = 0; fx < 12; fx++) {
            for (int fz = 0; fz < 12; fz++) {
                Set<Material> inField = EnumSet.noneOf(Material.class);
                for (int x = 0; x < VegetationPlan.FIELD_SIZE; x++) {
                    for (int z = 0; z < VegetationPlan.FIELD_SIZE; z++) {
                        Column column = Column.plain(cropland, Material.GRASS_BLOCK,
                                fx * VegetationPlan.FIELD_SIZE + x, fz * VegetationPlan.FIELD_SIZE + z);
                        switch (plan.choose(column, random)) {
                            case Placement.Crop crop -> {
                                crops++;
                                if (crop.crop() != null) {
                                    inField.add(crop.crop());
                                    cropKinds.add(crop.crop());
                                }
                            }
                            case Placement.Irrigation ignored -> channels++;
                            default -> meadow++;
                        }
                    }
                }
                assertThat(inField.size()).as("one crop per field").isLessThanOrEqualTo(1);
            }
        }
        int total = 144 * VegetationPlan.FIELD_SIZE * VegetationPlan.FIELD_SIZE;
        assertThat(crops).isGreaterThan(total / 2);
        assertThat(channels).isBetween(total / 12, total / 6);
        assertThat(meadow).isLessThan(total / 3); // fallow fields and the gaps in a pumpkin patch
        assertThat(cropKinds).contains(Material.WHEAT, Material.POTATOES, Material.CARROTS, Material.BEETROOTS,
                Material.PUMPKIN_STEM);
    }

    @Test
    void slopingCroplandAndDisabledFarmlandStayMeadow() {
        TerrainSample cropland = sample(ClimateBiome.GRASSLAND, LandcoverClass.CROPLAND);
        Random random = new Random(19);
        VegetationPlan plan = new VegetationPlan(1.0);
        VegetationPlan noFields = new VegetationPlan(1.0, true, false);
        for (int i = 0; i < 2_000; i++) {
            assertThat(plan.choose(Column.plain(cropland, Material.GRASS_BLOCK, i, 0).onSlope(), random))
                    .isNotInstanceOfAny(Placement.Crop.class, Placement.Irrigation.class);
            assertThat(noFields.choose(Column.plain(cropland, Material.GRASS_BLOCK, i, 0), random))
                    .isNotInstanceOfAny(Placement.Crop.class, Placement.Irrigation.class);
        }
    }

    @Test
    void customTreesCanBeSwitchedOff() {
        VegetationPlan vanillaOnly = new VegetationPlan(1.0, false, true);
        TerrainSample savanna = sample(ClimateBiome.SAVANNA, LandcoverClass.SHRUBLAND);
        Random random = new Random(23);
        for (int i = 0; i < COLUMNS; i++) {
            assertThat(vanillaOnly.choose(Column.plain(savanna, Material.GRASS_BLOCK, i, 0), random))
                    .isNotInstanceOf(Placement.CustomTree.class);
        }
    }

    /** Regression for the 2026-09-03 chunk-system crash: tall_dry_grass is one block high, not two. */
    @Test
    void tallPlantsAreOnlyTheTwoBlockOnes() {
        Set<Material> twoBlock = EnumSet.of(Material.TALL_GRASS, Material.LARGE_FERN, Material.SUNFLOWER,
                Material.LILAC, Material.PEONY, Material.ROSE_BUSH, Material.PITCHER_PLANT);
        VegetationPlan plan = new VegetationPlan(1.0);
        Random random = new Random(37);
        for (ClimateBiome biome : ClimateBiome.values()) {
            for (LandcoverClass cover : LandcoverClass.values()) {
                TerrainSample sample = sample(biome, cover);
                for (Material ground : new Material[] {Material.GRASS_BLOCK, Material.SAND, Material.TERRACOTTA,
                    Material.PODZOL, Material.MUD}) {
                    for (int i = 0; i < 300; i++) {
                        Column column = Column.plain(sample, ground, i, i % 17).nearFreshWater(1 + i % 5, i % 2 == 0);
                        if (plan.choose(column, random) instanceof Placement.TallPlant tall) {
                            assertThat(tall.material()).isIn(twoBlock);
                        }
                    }
                }
            }
        }
    }

    @Test
    void nothingGrowsOnIceSnowRockOrDryWater() {
        assertThat(tally(ClimateBiome.GLACIER, LandcoverClass.SNOW_ICE, Material.SNOW_BLOCK, 1.0).anything()).isZero();
        assertThat(tally(ClimateBiome.TUNDRA, LandcoverClass.MOSS_LICHEN, Material.SNOW_BLOCK, 1.0).anything()).isZero();
        assertThat(tally(ClimateBiome.BARE_ROCK, LandcoverClass.BARE_SPARSE, Material.STONE, 1.0).anything()).isZero();
        assertThat(tally(ClimateBiome.TEMPERATE_FOREST, LandcoverClass.TREE_COVER, Material.STONE, 1.0).trees).isZero();
    }

    @Test
    void lakesAndRiversCarryLilyPadsAndSeagrass() {
        Tally shallow = water(ClimateBiome.LAKE, WaterType.LAKE, 2);
        Tally deep = water(ClimateBiome.LAKE, WaterType.LAKE, 8);
        assertThat(shallow.lilyPads).isBetween(COLUMNS / 30, COLUMNS / 8);
        assertThat(shallow.seagrass).isGreaterThan(COLUMNS / 10);
        assertThat(deep.lilyPads).isZero();
        assertThat(water(ClimateBiome.FROZEN_OCEAN, WaterType.LAKE, 2).anything()).isZero();
    }

    @Test
    void warmSeasGrowReefsAndTemperateSeasGrowKelp() {
        Tally warm = water(ClimateBiome.WARM_OCEAN, WaterType.OCEAN, 8);
        Tally temperate = water(ClimateBiome.OCEAN, WaterType.OCEAN, 12);
        Tally frozen = water(ClimateBiome.FROZEN_OCEAN, WaterType.OCEAN, 12);
        Tally abyss = water(ClimateBiome.DEEP_OCEAN, WaterType.OCEAN, 60);

        assertThat(warm.coral).isGreaterThan(COLUMNS / 40);
        assertThat(warm.pickles).isGreaterThan(0);
        assertThat(temperate.coral).isZero();
        assertThat(temperate.kelp).isGreaterThan(COLUMNS / 40);
        assertThat(warm.kelp).isZero();
        assertThat(frozen.anything()).isZero();
        assertThat(abyss.coral + abyss.kelp).isZero();
        assertThat(abyss.seagrass).isLessThan(COLUMNS / 10);
    }

    @Test
    void kelpNeverReachesTheSurface() {
        VegetationPlan plan = new VegetationPlan(1.0);
        Random random = new Random(29);
        for (int depth = 1; depth < 30; depth++) {
            TerrainSample sea = new TerrainSample(-depth * 20.0, 60 - depth, WaterType.OCEAN, 60,
                    LandcoverClass.PERMANENT_WATER, ClimateBiome.OCEAN, false);
            for (int i = 0; i < 400; i++) {
                if (plan.choose(Column.plain(sea, Material.GRAVEL, i, depth), random) instanceof Placement.Kelp kelp) {
                    assertThat(kelp.height()).isLessThan(depth);
                }
            }
        }
    }

    @Test
    void densityScalesEverythingAndZeroDisablesIt() {
        Tally full = tally(ClimateBiome.TEMPERATE_FOREST, LandcoverClass.TREE_COVER, Material.GRASS_BLOCK, 1.0);
        Tally half = tally(ClimateBiome.TEMPERATE_FOREST, LandcoverClass.TREE_COVER, Material.GRASS_BLOCK, 0.5);
        Tally none = tally(ClimateBiome.TEMPERATE_FOREST, LandcoverClass.TREE_COVER, Material.GRASS_BLOCK, 0.0);

        assertThat(half.trees + half.custom).isBetween((int) ((full.trees + full.custom) * 0.35),
                (int) ((full.trees + full.custom) * 0.65));
        assertThat(none.anything()).isZero();
        assertThat(water(ClimateBiome.WARM_OCEAN, WaterType.OCEAN, 8, 0.0).anything()).isZero();
    }

    @Test
    void theSameDrawGivesTheSamePlacement() {
        TerrainSample forest = sample(ClimateBiome.TEMPERATE_FOREST, LandcoverClass.TREE_COVER);
        VegetationPlan plan = new VegetationPlan(1.0);
        for (int seed = 0; seed < 200; seed++) {
            Column column = Column.plain(forest, Material.GRASS_BLOCK, seed * 7, seed * 3).nearFreshWater(2, true);
            assertThat(plan.choose(column, new Random(seed))).isEqualTo(plan.choose(column, new Random(seed)));
        }
    }

    private static boolean isFlower(Material material) {
        return switch (material) {
            case DANDELION, POPPY, OXEYE_DAISY, CORNFLOWER, AZURE_BLUET, RED_TULIP, ORANGE_TULIP, WHITE_TULIP,
                 PINK_TULIP, ALLIUM, WILDFLOWERS, LILY_OF_THE_VALLEY -> true;
            default -> false;
        };
    }

    private static TerrainSample sample(ClimateBiome biome, LandcoverClass cover) {
        return new TerrainSample(300.0, 78, WaterType.NONE, 63, cover, biome, false);
    }

    private static Tally tally(ClimateBiome biome, LandcoverClass cover, Material ground, double density) {
        VegetationPlan plan = new VegetationPlan(density);
        TerrainSample sample = sample(biome, cover);
        Random random = new Random(42);
        Tally tally = new Tally();
        for (int i = 0; i < COLUMNS; i++) {
            // Spread the columns over ground, so the patch noise is sampled and not one cell.
            tally.add(plan.choose(Column.plain(sample, ground, i % 141, i / 141), random));
        }
        return tally;
    }

    private static Tally water(ClimateBiome biome, WaterType type, int depth) {
        return water(biome, type, depth, 1.0);
    }

    private static Tally water(ClimateBiome biome, WaterType type, int depth, double density) {
        VegetationPlan plan = new VegetationPlan(density);
        TerrainSample sample = new TerrainSample(-depth * 20.0, 63 - depth, type, 63,
                LandcoverClass.PERMANENT_WATER, biome, false);
        Random random = new Random(31);
        Tally tally = new Tally();
        for (int i = 0; i < COLUMNS; i++) {
            tally.add(plan.choose(Column.plain(sample, Material.GRAVEL, i % 141, i / 141), random));
        }
        return tally;
    }

    private static final class Tally {
        int trees;
        int custom;
        int plants;
        int cacti;
        int reeds;
        int lilyPads;
        int seagrass;
        int kelp;
        int coral;
        int pickles;
        final Set<TreeType> treeTypes = EnumSet.noneOf(TreeType.class);
        final Set<Material> plantMaterials = EnumSet.noneOf(Material.class);
        final Map<Shape, Integer> shapes = new EnumMap<>(Shape.class);

        void add(Placement placement) {
            switch (placement) {
                case Placement.Tree tree -> {
                    trees++;
                    treeTypes.add(tree.type());
                }
                case Placement.CustomTree tree -> {
                    custom++;
                    shapes.merge(shapeOf(tree.blueprint()), 1, Integer::sum);
                }
                case Placement.Plant plant -> {
                    plants++;
                    plantMaterials.add(plant.material());
                }
                case Placement.TallPlant plant -> {
                    plants++;
                    plantMaterials.add(plant.material());
                }
                case Placement.Cactus cactus -> cacti++;
                case Placement.SugarCane cane -> reeds++;
                case Placement.Bamboo bamboo -> plants++;
                case Placement.LilyPad pad -> lilyPads++;
                case Placement.Seagrass grass -> seagrass++;
                case Placement.Kelp k -> kelp++;
                case Placement.Coral c -> coral++;
                case Placement.SeaPickles p -> pickles++;
                case Placement.Crop crop -> plants++;
                case Placement.Irrigation irrigation -> { }
                case Placement.None none -> { }
            }
        }

        Set<Shape> customShapes() {
            return shapes.keySet();
        }

        int anything() {
            return trees + custom + plants + cacti + reeds + lilyPads + seagrass + kelp + coral + pickles;
        }

        /** Recognise a shape from its silhouette; the plan does not label blueprints. */
        private static Shape shapeOf(TreeBlueprint blueprint) {
            boolean leaves = blueprint.voxels().stream().anyMatch(v -> v.kind() == TreeBlueprint.Kind.LEAVES);
            boolean moss = blueprint.voxels().stream().anyMatch(v -> v.kind() == TreeBlueprint.Kind.MOSS
                    || v.kind() == TreeBlueprint.Kind.MUSHROOM);
            int height = blueprint.height();
            int radius = blueprint.radius();
            Material log = blueprint.wood().log();
            if (!leaves) {
                return height <= 2 ? Shape.FALLEN_LOG : Shape.DEAD_TREE;
            }
            if (moss) {
                return Shape.FALLEN_LOG;
            }
            if (log == Material.JUNGLE_LOG) {
                return Shape.PALM;
            }
            if (log == Material.ACACIA_LOG) {
                return radius >= 4 ? Shape.BAOBAB : Shape.SHRUB;
            }
            if (log == Material.SPRUCE_LOG) {
                return height >= 12 ? Shape.TALL_PINE : Shape.CYPRESS;
            }
            return height <= 3 ? Shape.SHRUB : Shape.WILLOW;
        }
    }
}
