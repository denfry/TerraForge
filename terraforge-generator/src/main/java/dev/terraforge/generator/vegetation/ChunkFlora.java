package dev.terraforge.generator.vegetation;

import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.core.terrain.TerrainSample;
import java.util.Random;
import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Cocoa;
import org.bukkit.generator.LimitedRegion;

/**
 * Passes that decide per chunk rather than per column, run after {@link VegetationPlan}'s columns:
 * extra dark oaks, cocoa on jungle trunks and dense kelp patches.
 *
 * <p>Each pass draws from its own random, seeded from the chunk, so turning one on never moves a tree
 * the columns placed or what another pass does. Everything is off unless configured.
 */
public final class ChunkFlora {

    /** Reads the geography of a column; the populator's cached window of the chunk and its ring. */
    @FunctionalInterface
    public interface Samples {
        TerrainSample at(int x, int z);
    }

    private static final long DARK_OAK_SALT = 0x44415242L;
    private static final long COCOA_SALT = 0x434F434FL;
    private static final long KELP_PATCH_SALT = 0x4B50414CL;

    /** Trees stand 5..10 blocks from the chunk edge, so a dark oak's crown stays in the region. */
    private static final int TREE_MARGIN = 5;
    private static final int TREE_SPAN = 6;
    /** Vanilla hangs cocoa only on the lowest logs of a trunk. */
    private static final int COCOA_LOGS = 3;
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    /** Vanilla's kelp decorator picks tip ages in this range. */
    private static final int KELP_AGE_MIN = 20;
    private static final int KELP_AGE_SPAN = 5;

    private final TerraForgeConfig.DarkOakSection darkOak;
    private final TerraForgeConfig.CocoaSection cocoa;
    private final TerraForgeConfig.KelpSection kelp;
    private final KelpLimit kelpLimit;

    public ChunkFlora(TerraForgeConfig.DarkOakSection darkOak, TerraForgeConfig.CocoaSection cocoa,
                      TerraForgeConfig.KelpSection kelp) {
        this.darkOak = darkOak;
        this.cocoa = cocoa;
        this.kelp = kelp;
        this.kelpLimit = new KelpLimit(kelp.lengthFraction(), kelp.maxHeight());
    }

    /** Whether any pass would do anything; a flora with none is never asked to run. */
    public boolean enabled() {
        return darkOak.temperateRainforest() > 0.0 || darkOak.temperateForest() > 0.0
                || cocoaOn() || kelp.patchesPerChunk() > 0.0;
    }

    private boolean cocoaOn() {
        return cocoa.treeChance() > 0.0 && cocoa.sideChance() > 0.0;
    }

    void populate(LimitedRegion region, Samples samples, int chunkX, int chunkZ, int minY, int maxY, long seed) {
        darkOaks(region, samples, chunkX, chunkZ, minY, maxY, new Random(seed ^ DARK_OAK_SALT));
        if (cocoaOn()) {
            cocoa(region, samples, chunkX, chunkZ, minY, maxY, new Random(seed ^ COCOA_SALT));
        }
        kelpPatches(region, samples, chunkX, chunkZ, minY, maxY, new Random(seed ^ KELP_PATCH_SALT));
    }

    // ---------------------------------------------------------------------------------------------
    // Dark oak

    double darkOakDensity(ClimateBiome biome) {
        return switch (biome) {
            case TEMPERATE_RAINFOREST -> darkOak.temperateRainforest();
            case TEMPERATE_FOREST -> darkOak.temperateForest();
            default -> 0.0;
        };
    }

    private void darkOaks(LimitedRegion region, Samples samples, int chunkX, int chunkZ, int minY, int maxY,
                          Random random) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        double density = darkOakDensity(samples.at(baseX + 8, baseZ + 8).biome());
        if (density <= 0.0) {
            return;
        }
        int wanted = count(density, random.nextDouble());
        int planted = 0;
        for (int attempt = 0; attempt < wanted * 4 && planted < wanted; attempt++) {
            int x = baseX + TREE_MARGIN + random.nextInt(TREE_SPAN);
            int z = baseZ + TREE_MARGIN + random.nextInt(TREE_SPAN);
            TerrainSample sample = samples.at(x, z);
            int y = Math.clamp(sample.surfaceY(), minY, maxY);
            if (y + 1 <= maxY && darkOakFits(region, samples, sample, x, y, z)
                    && region.generateTree(new Location(null, x, y + 1, z), random, TreeType.DARK_OAK)) {
                planted++;
            }
        }
    }

    /** A dark oak grows from a level 2x2 of soil with nothing but plants above it. */
    private boolean darkOakFits(LimitedRegion region, Samples samples, TerrainSample sample, int x, int y, int z) {
        if (darkOakDensity(sample.biome()) <= 0.0) {
            return false;
        }
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                TerrainSample corner = samples.at(x + dx, z + dz);
                if (corner.isWater() || corner.surfaceY() != sample.surfaceY()
                        || !isTreeSoil(region.getType(x + dx, y, z + dz))
                        || !isFree(region.getType(x + dx, y + 1, z + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isTreeSoil(Material material) {
        return material == Material.GRASS_BLOCK || material == Material.DIRT
                || material == Material.PODZOL || material == Material.COARSE_DIRT;
    }

    private static boolean isFree(Material material) {
        return material.isAir() || (!material.isSolid() && material != Material.WATER && material != Material.LAVA);
    }

    // ---------------------------------------------------------------------------------------------
    // Cocoa

    private void cocoa(LimitedRegion region, Samples samples, int chunkX, int chunkZ, int minY, int maxY,
                       Random random) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = baseX + localX;
                int z = baseZ + localZ;
                TerrainSample sample = samples.at(x, z);
                int bottom = Math.clamp(sample.surfaceY(), minY, maxY) + 1;
                if (bottom > maxY || !growsCocoa(sample.biome()) || !isTrunk(region, x, bottom, z)
                        || !isTrunkSoil(region.getType(x, bottom - 1, z))
                        || random.nextDouble() >= cocoa.treeChance()) {
                    continue;
                }
                for (int y = bottom; y < bottom + COCOA_LOGS && y <= maxY && isTrunk(region, x, y, z); y++) {
                    for (BlockFace side : SIDES) {
                        hangPod(region, x, y, z, side, random);
                    }
                }
            }
        }
    }

    private static boolean growsCocoa(ClimateBiome biome) {
        return biome == ClimateBiome.TROPICAL_RAINFOREST || biome == ClimateBiome.TROPICAL_SEASONAL_FOREST;
    }

    /** An upright jungle log; a fallen one lies on its side and bears no pods. */
    private static boolean isTrunk(LimitedRegion region, int x, int y, int z) {
        return region.getType(x, y, z) == Material.JUNGLE_LOG
                && region.getBlockData(x, y, z) instanceof Orientable log && log.getAxis() == Axis.Y;
    }

    private static boolean isTrunkSoil(Material material) {
        return isTreeSoil(material) || material == Material.ROOTED_DIRT || material == Material.MUD;
    }

    private void hangPod(LimitedRegion region, int logX, int y, int logZ, BlockFace side, Random random) {
        int x = logX + side.getModX();
        int z = logZ + side.getModZ();
        if (random.nextDouble() >= cocoa.sideChance() || !region.isInRegion(x, y, z)
                || !region.getType(x, y, z).isAir()) {
            return;
        }
        Cocoa pod = (Cocoa) Material.COCOA.createBlockData();
        pod.setFacing(side.getOppositeFace()); // a pod faces the log it hangs from
        pod.setAge(random.nextInt(pod.getMaximumAge() + 1));
        region.setBlockData(x, y, z, pod);
    }

    // ---------------------------------------------------------------------------------------------
    // Kelp

    private void kelpPatches(LimitedRegion region, Samples samples, int chunkX, int chunkZ, int minY, int maxY,
                             Random random) {
        if (kelp.patchesPerChunk() <= 0.0) {
            return;
        }
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int radius = kelp.patchRadius();
        int patches = count(kelp.patchesPerChunk(), random.nextDouble());
        for (int patch = 0; patch < patches; patch++) {
            int centreX = random.nextInt(16);
            int centreZ = random.nextInt(16);
            if (!growsKelp(samples.at(baseX + centreX, baseZ + centreZ).biome())) {
                continue;
            }
            for (int column = 0; column < kelp.columnsPerPatch(); column++) {
                // Clamped to the chunk: a patch at the edge is denser, never spilling into a neighbour.
                int x = baseX + Math.clamp(centreX - radius + random.nextInt(radius * 2 + 1), 0, 15);
                int z = baseZ + Math.clamp(centreZ - radius + random.nextInt(radius * 2 + 1), 0, 15);
                plantKelp(region, samples.at(x, z), x, z, minY, maxY, random);
            }
        }
    }

    /** Warm seas grow reefs and frozen ones nothing, as {@link VegetationPlan} has it. */
    static boolean growsKelp(ClimateBiome biome) {
        return biome == ClimateBiome.OCEAN || biome == ClimateBiome.DEEP_OCEAN;
    }

    private void plantKelp(LimitedRegion region, TerrainSample sample, int x, int z, int minY, int maxY,
                           Random random) {
        int floor = Math.clamp(sample.surfaceY(), minY, maxY);
        int surface = Math.min(sample.waterSurfaceY(), maxY);
        if (!sample.isWater() || !growsKelp(sample.biome()) || surface <= floor) {
            return;
        }
        Material bed = region.getType(x, floor, z);
        if (bed == Material.MAGMA_BLOCK || !bed.isSolid()) {
            return;
        }
        for (int y = floor + 1; y <= surface; y++) {
            Material block = region.getType(x, y, z);
            if (block != Material.WATER && !(y == floor + 1 && block == Material.SEAGRASS)) {
                return;
            }
        }
        int gap = kelp.surfaceGapMin() + random.nextInt(kelp.surfaceGapMax() - kelp.surfaceGapMin() + 1);
        int top = kelpTop(floor, surface, gap, kelpLimit);
        for (int y = floor + 1; y < top; y++) {
            region.setType(x, y, z, Material.KELP_PLANT);
        }
        Ageable tip = (Ageable) Material.KELP.createBlockData();
        int age = KELP_AGE_MIN + random.nextInt(KELP_AGE_SPAN);
        tip.setAge(kelpLimit.stopsGrowth() ? tip.getMaximumAge() : Math.min(tip.getMaximumAge(), age));
        region.setBlockData(x, top, z, tip);
    }

    /** The stalk's top: {@code gap} below the water, cut by the limit, and always one block above the bed. */
    static int kelpTop(int floor, int surface, int gap, KelpLimit limit) {
        return floor + limit.apply(Math.max(1, surface - gap - floor));
    }

    /** The whole part of a density, plus one more with the probability of the fraction. */
    static int count(double density, double roll) {
        int whole = (int) Math.floor(density);
        return roll < density - whole ? whole + 1 : whole;
    }
}
