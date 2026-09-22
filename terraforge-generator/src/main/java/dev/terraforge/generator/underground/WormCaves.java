package dev.terraforge.generator.underground;

import dev.terraforge.generator.noise.CellNoise;
import dev.terraforge.generator.pipeline.ChunkSampler;
import java.util.SplittableRandom;

/**
 * Vanilla 1.16's winding cave systems -- tunnels, rooms, forks -- in this world's vertical frame.
 *
 * <p>A system starts in one chunk and wanders up to {@link #RANGE_CHUNKS} chunks away, so every chunk
 * replays the systems of every chunk within that range from their own seeds and keeps the part that
 * falls inside itself. Nothing depends on generation order: a tunnel crossing a chunk border is carved
 * half by each chunk and meets itself exactly.
 *
 * <p>Two rules keep a cave from breaking the world, and both come from each column's own data rather
 * than from what happens to be in the chunk: a cave stays {@link #ROOF} blocks under the ground, and
 * {@link #WATER_MARGIN} further under any water within that many columns. So a cave never drains a lake,
 * never opens under a river, never breaches the seabed -- including water just over the chunk edge,
 * which the sampler's shore margin lets this chunk see.
 */
final class WormCaves {

    static final int RANGE_CHUNKS = 8;

    /** Blocks of ground left above every cave: the soil and a little stone, so no hole opens up. */
    static final int ROOF = 6;

    /** Columns of clearance kept from water sideways, and extra blocks of rock kept under it. */
    static final int WATER_MARGIN = 4;

    private static final long SALT = 0x636176655F737973L;

    /** Vanilla 1.16 starts caves at y = nextInt(nextInt(120) + 8): at most 127, mostly low. */
    private static final int VANILLA_START_BOUND = 120;

    private final double systemChance;
    private final int floorY;
    private final int vanillaBottom;

    /**
     * @param rarity   one system per this many chunks; vanilla 1.16 is 7
     * @param floorY   lowest Y above the bedrock
     * @param seaLevel this world's sea level, which places vanilla's cave band
     */
    WormCaves(double rarity, int floorY, int seaLevel) {
        this.systemChance = 1.0 / Math.max(1.0, rarity);
        this.floorY = floorY;
        this.vanillaBottom = seaLevel - OreTable.VANILLA_SEA_LEVEL;
    }

    void carve(int chunkX, int chunkZ, UndergroundCells cells, ChunkSampler.ChunkSamples samples) {
        Target target = Target.of(chunkX, chunkZ, cells, samples);
        if (target == null) {
            return;
        }
        for (int originZ = chunkZ - RANGE_CHUNKS; originZ <= chunkZ + RANGE_CHUNKS; originZ++) {
            for (int originX = chunkX - RANGE_CHUNKS; originX <= chunkX + RANGE_CHUNKS; originX++) {
                systems(target, originX, originZ);
            }
        }
    }

    private void systems(Target target, int originX, int originZ) {
        SplittableRandom random = new SplittableRandom(CellNoise.hash(originX, originZ, SALT));
        int systems = random.nextInt(random.nextInt(random.nextInt(15) + 1) + 1);
        if (random.nextDouble() >= systemChance) {
            return;
        }
        for (int system = 0; system < systems; system++) {
            double x = (originX << 4) + random.nextInt(16);
            double y = startY(random);
            double z = (originZ << 4) + random.nextInt(16);
            int tunnels = 1;
            if (random.nextInt(4) == 0) {
                tunnel(target, random.nextLong(), x, y, z, 1.0F + random.nextFloat() * 6.0F, 0.0F, 0.0F, -1, -1, 0.5);
                tunnels += random.nextInt(4);
            }
            for (int tunnel = 0; tunnel < tunnels; tunnel++) {
                float yaw = random.nextFloat() * (float) Math.PI * 2.0F;
                float pitch = (random.nextFloat() - 0.5F) * 2.0F / 8.0F;
                float width = random.nextFloat() * 2.0F + random.nextFloat();
                if (random.nextInt(10) == 0) {
                    width *= random.nextFloat() * random.nextFloat() * 3.0F + 1.0F;
                }
                tunnel(target, random.nextLong(), x, y, z, width, yaw, pitch, 0, 0, 1.0);
            }
        }
    }

    /**
     * Vanilla's start height, moved with the sea; in a world deeper than vanilla's, a share of the
     * systems starts in the extra rock below, in proportion to how much of it there is.
     */
    private double startY(SplittableRandom random) {
        int deepTop = vanillaBottom - 1;
        int deepBottom = floorY + 8;
        int vanilla = vanillaBottom + random.nextInt(random.nextInt(VANILLA_START_BOUND) + 8);
        if (deepTop <= deepBottom) {
            return vanilla;
        }
        double deepHeight = deepTop - deepBottom;
        double deepShare = Math.min(0.5, deepHeight / (deepHeight + 128.0));
        if (random.nextDouble() < deepShare) {
            return deepBottom + random.nextInt((int) deepHeight + 1);
        }
        return vanilla;
    }

    private void tunnel(Target target, long seed, double x, double y, double z, float width, float yaw,
                        float pitch, int step, int maxSteps, double yScale) {
        SplittableRandom random = new SplittableRandom(seed);
        double centreX = target.minX + 8;
        double centreZ = target.minZ + 8;
        float yawChange = 0.0F;
        float pitchChange = 0.0F;
        if (maxSteps <= 0) {
            int length = RANGE_CHUNKS * 16 - 16;
            maxSteps = length - random.nextInt(length / 4);
        }
        boolean room = false;
        if (step == -1) {
            step = maxSteps / 2;
            room = true;
        }
        int split = random.nextInt(maxSteps / 2) + maxSteps / 4;
        boolean steep = random.nextInt(6) == 0;
        for (; step < maxSteps; step++) {
            double radiusH = 1.5 + Math.sin(step * Math.PI / maxSteps) * width;
            double radiusV = radiusH * yScale;
            float cosPitch = (float) Math.cos(pitch);
            x += Math.cos(yaw) * cosPitch;
            y += Math.sin(pitch);
            z += Math.sin(yaw) * cosPitch;
            pitch *= steep ? 0.92F : 0.7F;
            pitch += pitchChange * 0.1F;
            yaw += yawChange * 0.1F;
            pitchChange *= 0.9F;
            yawChange *= 0.75F;
            pitchChange += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 2.0F;
            yawChange += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 4.0F;
            if (!room && step == split && width > 1.0F) {
                tunnel(target, random.nextLong(), x, y, z, random.nextFloat() * 0.5F + 0.5F,
                        yaw - (float) (Math.PI / 2), pitch / 3.0F, step, maxSteps, 1.0);
                tunnel(target, random.nextLong(), x, y, z, random.nextFloat() * 0.5F + 0.5F,
                        yaw + (float) (Math.PI / 2), pitch / 3.0F, step, maxSteps, 1.0);
                return;
            }
            if (room || random.nextInt(4) != 0) {
                double dx = x - centreX;
                double dz = z - centreZ;
                double remaining = maxSteps - step;
                double reach = width + 2.0 + 16.0;
                // Can no longer reach this chunk before it ends: stop simulating it.
                if (dx * dx + dz * dz - remaining * remaining > reach * reach) {
                    return;
                }
                double margin = 16.0 + radiusH * 2.0;
                if (Math.abs(dx) <= margin && Math.abs(dz) <= margin) {
                    target.sphere(x, y, z, radiusH, radiusV);
                }
                if (room) {
                    return;
                }
            }
        }
    }

    /** The chunk being carved, with each column's ceiling already worked out. */
    private static final class Target {
        final int minX;
        final int minZ;
        final UndergroundCells cells;
        final int[] ceilings;
        final int highest;

        private Target(int minX, int minZ, UndergroundCells cells, int[] ceilings, int highest) {
            this.minX = minX;
            this.minZ = minZ;
            this.cells = cells;
            this.ceilings = ceilings;
            this.highest = highest;
        }

        static Target of(int chunkX, int chunkZ, UndergroundCells cells, ChunkSampler.ChunkSamples samples) {
            if (cells.topY() <= cells.floorY()) {
                return null;
            }
            int margin = Math.min(WATER_MARGIN, samples.reach());
            int[] ceilings = new int[256];
            int highest = Integer.MIN_VALUE;
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    int ceiling = samples.surfaceY(localX, localZ) - ROOF;
                    for (int dz = -margin; dz <= margin; dz++) {
                        for (int dx = -margin; dx <= margin; dx++) {
                            if (samples.isWater(localX + dx, localZ + dz)) {
                                ceiling = Math.min(ceiling,
                                        samples.surfaceY(localX + dx, localZ + dz) - ROOF - WATER_MARGIN);
                            }
                        }
                    }
                    ceiling = Math.min(ceiling, cells.topY() - 1);
                    ceilings[localZ << 4 | localX] = ceiling;
                    highest = Math.max(highest, ceiling);
                }
            }
            if (highest < cells.floorY()) {
                return null;
            }
            return new Target(chunkX << 4, chunkZ << 4, cells, ceilings, highest);
        }

        void sphere(double x, double y, double z, double radiusH, double radiusV) {
            int fromY = Math.max(cells.floorY(), (int) Math.floor(y - radiusV) - 1);
            int toY = (int) Math.floor(y + radiusV) + 1;
            if (fromY > highest || toY < cells.floorY()) {
                return;
            }
            int fromX = Math.max(minX, (int) Math.floor(x - radiusH) - 1);
            int toX = Math.min(minX + 16, (int) Math.floor(x + radiusH) + 1);
            int fromZ = Math.max(minZ, (int) Math.floor(z - radiusH) - 1);
            int toZ = Math.min(minZ + 16, (int) Math.floor(z + radiusH) + 1);
            for (int blockX = fromX; blockX < toX; blockX++) {
                double dx = (blockX + 0.5 - x) / radiusH;
                for (int blockZ = fromZ; blockZ < toZ; blockZ++) {
                    double dz = (blockZ + 0.5 - z) / radiusH;
                    double horizontal = dx * dx + dz * dz;
                    if (horizontal >= 1.0) {
                        continue;
                    }
                    int localX = blockX - minX;
                    int localZ = blockZ - minZ;
                    int ceiling = Math.min(toY, ceilings[localZ << 4 | localX]);
                    for (int blockY = fromY; blockY <= ceiling; blockY++) {
                        double dy = (blockY + 0.5 - y) / radiusV;
                        // Vanilla's flat floor: the bottom of each sphere is left in place.
                        if (dy > -0.7 && horizontal + dy * dy < 1.0
                                && UndergroundCells.carvable(cells.get(localX, blockY, localZ))) {
                            cells.set(localX, blockY, localZ, UndergroundCells.CARVED);
                        }
                    }
                }
            }
        }
    }
}
