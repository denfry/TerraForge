package dev.terraforge.generator.vegetation;

import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.generator.pipeline.ChunkSampler;
import dev.terraforge.generator.pipeline.TerrainPipeline;
import dev.terraforge.generator.vegetation.TreeBlueprint.Kind;
import dev.terraforge.generator.vegetation.TreeBlueprint.Voxel;
import dev.terraforge.generator.vegetation.VegetationPlan.Placement;
import java.util.Random;
import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Bamboo;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.SeaPickle;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

/**
 * Places TerraForge's vegetation on a freshly generated chunk.
 *
 * <p>Runs as a Paper {@link BlockPopulator}, off the main thread, on a {@link LimitedRegion} that
 * covers the chunk and a one-chunk buffer around it, so a tree at a chunk edge grows whole. The
 * geography read is the chunk's own samples and a {@link Column#RADIUS}-block ring of its
 * neighbours', which is how a column knows it stands on a river bank or a warm coast. What grows,
 * and how much, is {@link VegetationPlan}'s decision.
 *
 * <p>Randomness is seeded from the chunk coordinates alone, never from Paper's supplied random:
 * two servers with the same data and config place the same trees, whatever their world seeds.
 */
public final class VegetationPopulator extends BlockPopulator {

    /** Mixed into the chunk-coordinate seed so vegetation never correlates with another stage. */
    private static final long SALT = 0x7E9E7A7A0B3C5D1FL;

    private final TerrainPipeline pipeline;
    private final VegetationPlan plan;

    public VegetationPopulator(TerrainPipeline pipeline, VegetationPlan plan) {
        this.pipeline = pipeline;
        this.plan = plan;
    }

    @Override
    public void populate(WorldInfo worldInfo, Random ignored, int chunkX, int chunkZ, LimitedRegion region) {
        Window window = new Window(pipeline, chunkX, chunkZ);
        Random random = new Random(seed(chunkX, chunkZ));
        int minY = worldInfo.getMinHeight();
        int maxY = worldInfo.getMaxHeight() - 1;

        for (int localZ = 0; localZ < ChunkSampler.ChunkSamples.SIZE; localZ++) {
            for (int localX = 0; localX < ChunkSampler.ChunkSamples.SIZE; localX++) {
                int x = (chunkX << 4) + localX;
                int z = (chunkZ << 4) + localZ;
                TerrainSample sample = window.at(x, z);
                int y = Math.clamp(sample.surfaceY(), minY, maxY);
                if (y + 1 > maxY) {
                    continue;
                }
                if (sample.isWater()) {
                    placeUnderwater(region, random, x, y, z, sample, window, maxY);
                    continue;
                }
                // The karst carver may have removed the surface; grow only on ground that is there.
                Material ground = region.getType(x, y, z);
                if (ground.isAir() || !region.getType(x, y + 1, z).isAir()) {
                    continue;
                }
                Column column = window.column(x, z, sample, ground);
                Placement placement = plan.choose(column, random);
                try {
                    place(region, random, x, y, z, maxY, placement);
                } catch (RuntimeException e) {
                    // One bad block must never take the chunk system down with it. Paper treats any
                    // exception out of a populator as unrecoverable and stops the server.
                    warnOnce(placement, e);
                }
            }
        }
    }

    private void placeUnderwater(LimitedRegion region, Random random, int x, int y, int z,
                                 TerrainSample sample, Window window, int maxY) {
        if (region.getType(x, y + 1, z) != Material.WATER) {
            return;
        }
        Column column = window.column(x, z, sample, region.getType(x, y, z));
        int surface = Math.min(sample.waterSurfaceY(), maxY);
        Placement placement = plan.choose(column, random);
        try {
            placeUnderwater(region, random, x, y, z, surface, maxY, placement);
        } catch (RuntimeException e) {
            warnOnce(placement, e);
        }
    }

    private static void placeUnderwater(LimitedRegion region, Random random, int x, int y, int z,
                                        int surface, int maxY, Placement placement) {
        switch (placement) {
            case Placement.LilyPad ignored -> {
                if (surface + 1 <= maxY && region.getType(x, surface, z) == Material.WATER
                        && region.getType(x, surface + 1, z).isAir()) {
                    region.setType(x, surface + 1, z, Material.LILY_PAD);
                }
            }
            case Placement.Seagrass seagrass -> {
                if (seagrass.tall() && region.getType(x, y + 2, z) == Material.WATER) {
                    region.setBlockData(x, y + 1, z, half(Material.TALL_SEAGRASS, Bisected.Half.BOTTOM));
                    region.setBlockData(x, y + 2, z, half(Material.TALL_SEAGRASS, Bisected.Half.TOP));
                } else {
                    region.setType(x, y + 1, z, Material.SEAGRASS);
                }
            }
            case Placement.Kelp kelp -> {
                int top = y;
                for (int i = 1; i <= kelp.height() && region.getType(x, y + i, z) == Material.WATER; i++) {
                    top = y + i;
                }
                if (top == y) {
                    return;
                }
                for (int cy = y + 1; cy < top; cy++) {
                    region.setType(x, cy, z, Material.KELP_PLANT);
                }
                Ageable tip = (Ageable) Material.KELP.createBlockData();
                tip.setAge(Math.min(tip.getMaximumAge(), 15 + random.nextInt(10)));
                region.setBlockData(x, top, z, tip);
            }
            case Placement.Coral coral -> {
                region.setType(x, y + 1, z, coral.block());
                if (coral.top() != null && region.getType(x, y + 2, z) == Material.WATER) {
                    region.setBlockData(x, y + 2, z, coral.top().createBlockData()); // waterlogged by default
                }
            }
            case Placement.SeaPickles pickles -> {
                SeaPickle data = (SeaPickle) Material.SEA_PICKLE.createBlockData();
                data.setPickles(Math.clamp(pickles.count(), data.getMinimumPickles(), data.getMaximumPickles()));
                region.setBlockData(x, y + 1, z, data);
            }
            default -> { }
        }
    }

    private static void place(LimitedRegion region, Random random, int x, int y, int z, int maxY,
                              Placement placement) {
        switch (placement) {
            case Placement.None ignored -> { }
            case Placement.Plant plant -> region.setType(x, y + 1, z, plant.material());
            case Placement.TallPlant plant -> {
                BlockData lower = plant.material().createBlockData();
                if (!(lower instanceof Bisected)) {
                    // A one-block plant mislabelled as tall (tall_dry_grass is one block high).
                    region.setBlockData(x, y + 1, z, lower);
                } else if (y + 2 <= maxY && region.getType(x, y + 2, z).isAir()) {
                    region.setBlockData(x, y + 1, z, half(plant.material(), Bisected.Half.BOTTOM));
                    region.setBlockData(x, y + 2, z, half(plant.material(), Bisected.Half.TOP));
                }
            }
            case Placement.Cactus cactus -> placeCactus(region, x, y, z, maxY, cactus);
            case Placement.SugarCane cane -> placeColumn(region, x, y, z, maxY, cane.height(), Material.SUGAR_CANE);
            case Placement.Bamboo bamboo -> placeBamboo(region, x, y, z, maxY, bamboo.height());
            case Placement.Tree tree -> region.generateTree(new Location(null, x, y + 1, z), random, tree.type());
            case Placement.CustomTree tree -> placeBlueprint(region, x, y, z, tree.blueprint());
            case Placement.Crop crop -> placeCrop(region, x, y, z, crop);
            case Placement.Irrigation ignored -> region.setType(x, y, z, Material.WATER);
            // Aquatic placements are handled by placeUnderwater; a dry column never receives one.
            case Placement.LilyPad ignored -> { }
            case Placement.Seagrass ignored -> { }
            case Placement.Kelp ignored -> { }
            case Placement.Coral ignored -> { }
            case Placement.SeaPickles ignored -> { }
        }
    }

    private static void placeCrop(LimitedRegion region, int x, int y, int z, Placement.Crop crop) {
        Farmland farmland = (Farmland) Material.FARMLAND.createBlockData();
        farmland.setMoisture(farmland.getMaximumMoisture());
        region.setBlockData(x, y, z, farmland);
        if (crop.crop() != null) {
            Ageable data = (Ageable) crop.crop().createBlockData();
            data.setAge(Math.clamp(crop.age(), 0, data.getMaximumAge()));
            region.setBlockData(x, y + 1, z, data);
        }
    }

    /** A cactus breaks against any solid neighbour, so it is only grown where all four sides are air. */
    private static void placeCactus(LimitedRegion region, int x, int y, int z, int maxY, Placement.Cactus cactus) {
        int placed = 0;
        for (int i = 1; i <= cactus.height(); i++) {
            int cy = y + i;
            if (cy > maxY || !region.isInRegion(x + 1, cy, z) || !region.isInRegion(x - 1, cy, z)
                    || !region.isInRegion(x, cy, z + 1) || !region.isInRegion(x, cy, z - 1)
                    || !region.getType(x, cy, z).isAir()
                    || !region.getType(x + 1, cy, z).isAir() || !region.getType(x - 1, cy, z).isAir()
                    || !region.getType(x, cy, z + 1).isAir() || !region.getType(x, cy, z - 1).isAir()) {
                break;
            }
            region.setType(x, cy, z, Material.CACTUS);
            placed++;
        }
        if (placed > 0 && cactus.flower() && y + placed + 1 <= maxY
                && region.getType(x, y + placed + 1, z).isAir()) {
            region.setType(x, y + placed + 1, z, Material.CACTUS_FLOWER);
        }
    }

    private static void placeColumn(LimitedRegion region, int x, int y, int z, int maxY, int height,
                                    Material material) {
        for (int i = 1; i <= height; i++) {
            if (y + i > maxY || !region.getType(x, y + i, z).isAir()) {
                return;
            }
            region.setType(x, y + i, z, material);
        }
    }

    private static void placeBamboo(LimitedRegion region, int x, int y, int z, int maxY, int height) {
        int top = 0;
        for (int i = 1; i <= height && y + i <= maxY && region.getType(x, y + i, z).isAir(); i++) {
            top = i;
        }
        for (int i = 1; i <= top; i++) {
            Bamboo stalk = (Bamboo) Material.BAMBOO.createBlockData();
            stalk.setAge(top >= 5 ? 1 : 0);
            stalk.setLeaves(i == top ? Bamboo.Leaves.LARGE : i == top - 1 ? Bamboo.Leaves.SMALL : Bamboo.Leaves.NONE);
            stalk.setStage(0);
            region.setBlockData(x, y + i, z, stalk);
        }
    }

    /**
     * A TerraForge tree, whole or not at all: if any block of it would fall outside the region it
     * is skipped, so no half tree is ever left at a chunk border. Logs replace leaves and plants,
     * leaves only fill air, and a trunk blocked by solid ground cancels the tree.
     */
    static void placeBlueprint(LimitedRegion region, int x, int y, int z, TreeBlueprint blueprint) {
        for (Voxel v : blueprint.voxels()) {
            int tx = x + v.dx();
            int ty = y + v.dy();
            int tz = z + v.dz();
            if (!region.isInRegion(tx, ty, tz)) {
                return;
            }
            if (v.kind() == Kind.LOG_Y && v.dx() == 0 && v.dz() == 0 && !replaceable(region.getType(tx, ty, tz))) {
                return;
            }
        }
        for (Voxel v : blueprint.voxels()) {
            int tx = x + v.dx();
            int ty = y + v.dy();
            int tz = z + v.dz();
            Material current = region.getType(tx, ty, tz);
            if (v.kind().isLog() ? !replaceable(current) : !current.isAir()) {
                continue;
            }
            region.setBlockData(tx, ty, tz, block(blueprint, v.kind()));
        }
    }

    private static boolean replaceable(Material material) {
        if (material.isAir()) {
            return true;
        }
        return switch (material) {
            case SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DEAD_BUSH, BUSH, SHORT_DRY_GRASS, TALL_DRY_GRASS,
                 LEAF_LITTER, MOSS_CARPET, PINK_PETALS, WILDFLOWERS, SNOW -> true;
            default -> material.name().endsWith("_LEAVES");
        };
    }

    private static BlockData block(TreeBlueprint blueprint, Kind kind) {
        return switch (kind) {
            case LOG_Y -> log(blueprint.wood().log(), Axis.Y);
            case LOG_X -> log(blueprint.wood().log(), Axis.X);
            case LOG_Z -> log(blueprint.wood().log(), Axis.Z);
            case LEAVES -> {
                Leaves leaves = (Leaves) blueprint.wood().leaves().createBlockData();
                leaves.setPersistent(true);
                yield leaves;
            }
            case MOSS -> Material.MOSS_CARPET.createBlockData();
            case MUSHROOM -> Material.BROWN_MUSHROOM.createBlockData();
        };
    }

    private static BlockData log(Material material, Axis axis) {
        Orientable log = (Orientable) material.createBlockData();
        log.setAxis(axis);
        return log;
    }

    private static BlockData half(Material material, Bisected.Half half) {
        BlockData data = material.createBlockData();
        if (data instanceof Bisected bisected) {
            bisected.setHalf(half);
        }
        return data;
    }

    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Logs a failed placement once per placement type, so a systematic bug is visible but not a flood. */
    private static void warnOnce(Placement placement, RuntimeException e) {
        String key = placement.getClass().getSimpleName() + ":" + e.getClass().getSimpleName();
        if (WARNED.add(key)) {
            LOG.log(System.Logger.Level.WARNING, "[TerraForge-Generator] vegetation placement {0} failed and was "
                    + "skipped; further failures of this kind are not logged", key, e);
        }
    }

    private static final System.Logger LOG = System.getLogger(VegetationPopulator.class.getName());

    /** A well-mixed seed from the chunk coordinates, independent of the world seed. */
    static long seed(int chunkX, int chunkZ) {
        long h = (chunkX * 0x9E3779B97F4A7C15L) ^ (chunkZ * 0xC2B2AE3D27D4EB4FL) ^ SALT;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return h;
    }

    /**
     * The chunk's samples and a ring of its neighbours', read lazily from the pipeline's cache, so
     * a column at a chunk edge sees the river or the coast on the far side of it.
     */
    static final class Window {
        private final TerrainPipeline pipeline;
        private final int chunkX;
        private final int chunkZ;
        private final ChunkSampler.ChunkSamples[] chunks = new ChunkSampler.ChunkSamples[9];

        Window(TerrainPipeline pipeline, int chunkX, int chunkZ) {
            this.pipeline = pipeline;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        TerrainSample at(int x, int z) {
            int cx = (x >> 4) - chunkX;
            int cz = (z >> 4) - chunkZ;
            int index = (cz + 1) * 3 + (cx + 1);
            ChunkSampler.ChunkSamples samples = chunks[index];
            if (samples == null) {
                samples = pipeline.sampleChunk(chunkX + cx, chunkZ + cz);
                chunks[index] = samples;
            }
            return samples.at(x & 15, z & 15);
        }

        Column column(int x, int z, TerrainSample sample, Material ground) {
            int y = sample.surfaceY();
            boolean flat = true;
            boolean atLevel = false;
            boolean warm = false;
            int waterDistance = Column.NO_WATER;
            for (int dz = -Column.RADIUS; dz <= Column.RADIUS; dz++) {
                for (int dx = -Column.RADIUS; dx <= Column.RADIUS; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    TerrainSample other = at(x + dx, z + dz);
                    int distance = Math.max(Math.abs(dx), Math.abs(dz));
                    if (distance == 1 && (other.isWater() || other.surfaceY() != y)) {
                        flat = false;
                    }
                    if (other.waterType() == WaterType.LAKE || other.waterType() == WaterType.RIVER) {
                        waterDistance = Math.min(waterDistance, distance);
                        boolean adjacent = Math.abs(dx) + Math.abs(dz) == 1;
                        if (adjacent && other.surfaceY() < y && y <= other.waterSurfaceY()) {
                            atLevel = true;
                        }
                    }
                    if (!warm && isWarm(other.biome())) {
                        warm = true;
                    }
                }
            }
            return new Column(sample, ground, x, z, flat, waterDistance, atLevel, warm);
        }

        private static boolean isWarm(ClimateBiome biome) {
            return switch (biome) {
                case TROPICAL_RAINFOREST, TROPICAL_SEASONAL_FOREST, SAVANNA, MANGROVE, DESERT, WARM_OCEAN -> true;
                default -> false;
            };
        }
    }
}
