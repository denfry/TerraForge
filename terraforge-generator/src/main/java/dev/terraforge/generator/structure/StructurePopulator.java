package dev.terraforge.generator.structure;

import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.generator.noise.CellNoise;
import dev.terraforge.generator.pipeline.ChunkSampler;
import dev.terraforge.generator.pipeline.TerrainPipeline;
import java.util.Arrays;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

/**
 * Scatters {@link StructureKind ruins and remains} over the land, one site at most per square of
 * {@code spacing} chunks.
 *
 * <p>Each square picks one chunk -- never on its edge, so a structure stays inside the region a
 * populator may write -- from a hash of the square's position alone: the same world comes out the
 * same whatever order its chunks generate in, and no seed is involved, like the rest of TerraForge.
 * The structure stands at the middle of that chunk, on ground levelled from the terrain samples, and
 * is skipped where the ground is water or too steep for it.
 *
 * <p>Runs before the vegetation, which grows only on open ground: no tree comes up through a floor.
 */
public final class StructurePopulator extends BlockPopulator {

    private static final Logger LOG = Logger.getLogger("TerraForge");
    private static final long SITE_SALT = 0x53495445L;
    private static final long KIND_SALT = 0x4B494E44L;
    /** Steepest ground, highest minus lowest column under the footprint, a site may have. */
    static final int MAX_RELIEF = 6;

    private final TerrainPipeline pipeline;
    private final int spacing;
    private final double chance;
    private volatile boolean warned;

    /**
     * @param spacing chunks along each side of the square that holds at most one site, at least 3
     * @param chance  share of squares that hold one, {@code 0..1}
     */
    public StructurePopulator(TerrainPipeline pipeline, int spacing, double chance) {
        if (spacing < 3) {
            throw new IllegalArgumentException("structure spacing must be at least 3 chunks: " + spacing);
        }
        this.pipeline = pipeline;
        this.spacing = spacing;
        this.chance = chance;
    }

    /** Whether this chunk is the site of its square; pure, for tests. */
    boolean isSite(int chunkX, int chunkZ) {
        int squareX = Math.floorDiv(chunkX, spacing);
        int squareZ = Math.floorDiv(chunkZ, spacing);
        long hash = CellNoise.hash(squareX, squareZ, SITE_SALT);
        if (CellNoise.unit(hash) >= chance) {
            return false;
        }
        int offsetX = 1 + (int) Math.floorMod(hash >>> 8, (long) spacing - 2);
        int offsetZ = 1 + (int) Math.floorMod(hash >>> 24, (long) spacing - 2);
        return chunkX == squareX * spacing + offsetX && chunkZ == squareZ * spacing + offsetZ;
    }

    @Override
    public void populate(WorldInfo worldInfo, Random ignored, int chunkX, int chunkZ, LimitedRegion region) {
        if (!isSite(chunkX, chunkZ)) {
            return;
        }
        try {
            place(worldInfo, chunkX, chunkZ, region);
        } catch (RuntimeException e) {
            // Paper treats an exception out of a populator as fatal; a lost ruin is not.
            if (!warned) {
                warned = true;
                LOG.log(Level.WARNING, "[TerraForge] A structure at chunk " + chunkX + "," + chunkZ
                        + " failed; further failures are not logged.", e);
            }
        }
    }

    private void place(WorldInfo worldInfo, int chunkX, int chunkZ, LimitedRegion region) {
        ChunkSampler.ChunkSamples samples = pipeline.sampleChunk(chunkX, chunkZ);
        int size = ChunkSampler.ChunkSamples.SIZE;
        boolean coast = false;
        TerrainSample land = null;
        int landDistance = Integer.MAX_VALUE;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                TerrainSample sample = samples.at(x, z);
                if (sample.waterType() == WaterType.OCEAN) {
                    coast = true;
                } else if (!sample.isWater()) {
                    int distance = Math.abs(x - 8) + Math.abs(z - 8);
                    if (distance < landDistance) {
                        landDistance = distance;
                        land = sample;
                    }
                }
            }
        }
        if (land == null) {
            return; // all water
        }
        ClimateBiome biome = land.biome();
        long hash = CellNoise.hash(chunkX, chunkZ, KIND_SALT);
        StructureKind kind = StructureKind.forSite(biome, coast, CellNoise.unit(hash));
        if (kind == null) {
            return;
        }
        Site site = flattest(samples, kind);
        if (site == null) {
            return;
        }
        int minY = worldInfo.getMinHeight();
        int maxY = worldInfo.getMaxHeight() - 1;
        if (site.floorY - StructureKind.CRYPT_DEPTH - 2 <= minY || site.floorY + kind.headroom + 2 >= maxY) {
            return;
        }
        Builder builder = new Builder(region, (chunkX << 4) + site.x, site.floorY, (chunkZ << 4) + site.z,
                minY, maxY, new Random(hash));
        builder.level(kind.radius, kind.headroom, kind.foundation(biome), kind.floor(biome), site.ground);
        kind.build(builder);
    }

    /** Where in the chunk a structure stands: its centre, floor and the ground under its footprint. */
    record Site(int x, int z, int floorY, int[][] ground) {
    }

    /**
     * The flattest dry footprint for this structure anywhere in the chunk, or {@code null} when every
     * one is wet or steeper than {@link #MAX_RELIEF}. Real land is rarely flat for nine blocks on end;
     * looking across the whole chunk instead of at its middle finds the bench on a hillside, the
     * terrace above a river. A wreck needs the opposite: a footprint that is partly sea.
     */
    static Site flattest(ChunkSampler.ChunkSamples samples, StructureKind kind) {
        int radius = kind.radius;
        int size = ChunkSampler.ChunkSamples.SIZE;
        Site best = null;
        int bestRelief = Integer.MAX_VALUE;
        int bestOffCentre = Integer.MAX_VALUE;
        // One block of rubble around the footprint stays inside the chunk as well.
        for (int cz = radius + 1; cz <= size - radius - 2; cz++) {
            for (int cx = radius + 1; cx <= size - radius - 2; cx++) {
                int low = Integer.MAX_VALUE;
                int high = Integer.MIN_VALUE;
                int wet = 0;
                int[] all = new int[(2 * radius + 1) * (2 * radius + 1)];
                int[][] ground = new int[2 * radius + 1][2 * radius + 1];
                int n = 0;
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        TerrainSample sample = samples.at(cx + dx, cz + dz);
                        if (sample.isWater()) {
                            wet++;
                        }
                        int y = sample.surfaceY();
                        ground[dz + radius][dx + radius] = y;
                        all[n++] = y;
                        low = Math.min(low, y);
                        high = Math.max(high, y);
                    }
                }
                boolean wreck = kind == StructureKind.SHIPWRECK;
                if (wreck ? wet == 0 || wet > n / 2 : wet > 0) {
                    continue;
                }
                if (samples.at(cx, cz).isWater()) {
                    continue;
                }
                int relief = high - low;
                if (relief > MAX_RELIEF && !wreck) {
                    continue;
                }
                int offCentre = Math.abs(cx - 8) + Math.abs(cz - 8);
                if (relief < bestRelief || relief == bestRelief && offCentre < bestOffCentre) {
                    Arrays.sort(all);
                    int floorY = wreck ? all[n - 1] - 1 : all[n / 2]; // a wreck lies aground, not in the surf
                    best = new Site(cx, cz, floorY, ground);
                    bestRelief = relief;
                    bestOffCentre = offCentre;
                }
            }
        }
        return best;
    }
}
