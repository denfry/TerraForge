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
 * than from what happens to be in the chunk: a closed cave stays {@link #ROOF} blocks under the ground,
 * and every cave stays {@link #WATER_MARGIN} further under any water within that many columns. So a
 * cave never drains a lake, never opens under a river, never breaches the seabed -- including water
 * just over the chunk edge, which the sampler's shore margin lets this chunk see.
 *
 * <p>A share of the systems, {@code entrances}, is open: allowed up through the soil to the open air,
 * as vanilla's are, so the hills have cave mouths and a player sees there is something below. Without
 * them every cave was sealed under six blocks of ground and could only be found by digging. Ravines
 * -- vanilla's canyons, tall and narrow -- come with the open systems and are always open.
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

    /** Share of {@code entrances} that is also the chance of a cave mouth in each chunk. */
    static final double MOUTH_SHARE = 0.25;
    private static final long MOUTH_SALT = 0x6D6F757468L;

    /** Vanilla 1.16 cuts a ravine from one chunk in fifty. */
    static final double RAVINE_CHANCE = 0.02;

    private final double systemChance;
    private final double entrances;
    private final int floorY;
    private final int vanillaBottom;

    /**
     * @param rarity   one system per this many chunks; vanilla 1.16 is 7
     * @param floorY   lowest Y above the bedrock
     * @param seaLevel this world's sea level, which places vanilla's cave band
     */
    WormCaves(double rarity, int floorY, int seaLevel) {
        this(rarity, 0.0, floorY, seaLevel);
    }

    /** @param entrances share of systems that may reach the surface, {@code 0..1}; {@code 0} also means no ravines */
    WormCaves(double rarity, double entrances, int floorY, int seaLevel) {
        this.systemChance = 1.0 / Math.max(1.0, rarity);
        this.entrances = entrances;
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
        if (entrances > 0.0) {
            for (int originZ = chunkZ - 1; originZ <= chunkZ + 1; originZ++) {
                for (int originX = chunkX - 1; originX <= chunkX + 1; originX++) {
                    mouth(target, samples, chunkX, chunkZ, originX, originZ);
                }
            }
        }
    }

    /**
     * A cave mouth: a sloping shaft from the surface down into the cave band, ending in a small
     * chamber. The worm systems start in vanilla's band, which follows the sea; where the land stands
     * a hundred blocks above it -- most mountains, most plateaus -- they never come near the surface,
     * open or not. A mouth starts at the surface itself, wherever that is.
     *
     * <p>Every chunk the shaft reaches must carve it identically, so everything about it comes from
     * the origin chunk's hash and from the one column it starts on, which every neighbour it can reach
     * sees through the sampler's margin: it spreads at most {@code reach} blocks from a start at least
     * that far inside its own chunk's neighbours' view. Its start must be dry land; water near its
     * path is kept away by the open ceilings, as for every open cave.
     */
    private void mouth(Target target, ChunkSampler.ChunkSamples samples, int chunkX, int chunkZ,
                       int originX, int originZ) {
        SplittableRandom random = new SplittableRandom(CellNoise.hash(originX, originZ, MOUTH_SALT));
        if (random.nextDouble() >= entrances * MOUTH_SHARE) {
            return;
        }
        int spread = Math.min(7, samples.reach());
        if (spread < 4) {
            return;
        }
        // Start where every chunk the mouth can reach sees the column: within `spread` of each edge it
        // may cross, and that edge's neighbour sees `reach` columns into this chunk.
        int startX = spread - 1 + random.nextInt(18 - 2 * spread);
        int startZ = spread - 1 + random.nextInt(18 - 2 * spread);
        int localX = startX + ((originX - chunkX) << 4);
        int localZ = startZ + ((originZ - chunkZ) << 4);
        int reach = samples.reach();
        if (localX < -reach || localX >= 16 + reach || localZ < -reach || localZ >= 16 + reach) {
            return; // too far to touch this chunk
        }
        if (samples.isWater(localX, localZ)) {
            return;
        }
        int top = samples.surfaceY(localX, localZ);
        int depth = 14 + random.nextInt(15);
        double radius = 1.6 + random.nextDouble() * 1.0;
        double driftX = (random.nextDouble() * 2.0 - 1.0) * (spread - radius - 1.0);
        double driftZ = (random.nextDouble() * 2.0 - 1.0) * (spread - radius - 1.0);
        double x0 = (originX << 4) + startX + 0.5;
        double z0 = (originZ << 4) + startZ + 0.5;
        for (int step = 0; step <= depth; step++) {
            double t = (double) step / depth;
            double r = radius * (1.0 - 0.3 * t) + 0.4 * Math.sin(step * 0.7);
            target.sphere(x0 + driftX * t, top + 1 - step, z0 + driftZ * t, Math.max(1.2, r), 1.5, true);
        }
        // A chamber at the bottom, where it most likely meets the systems around it.
        target.sphere(x0 + driftX, top - depth, z0 + driftZ, Math.min(spread - 0.5, radius + 1.5), 2.5, true);
    }

    private void systems(Target target, int originX, int originZ) {
        SplittableRandom random = new SplittableRandom(CellNoise.hash(originX, originZ, SALT));
        int systems = random.nextInt(random.nextInt(random.nextInt(15) + 1) + 1);
        // Drawn from their own stream, so entrances and ravines move no existing tunnel.
        SplittableRandom extra = new SplittableRandom(CellNoise.hash(originX, originZ, SALT + 1));
        boolean open = extra.nextDouble() < entrances;
        if (entrances > 0.0 && extra.nextDouble() < RAVINE_CHANCE) {
            ravine(target, extra, originX, originZ);
        }
        if (random.nextDouble() >= systemChance) {
            return;
        }
        for (int system = 0; system < systems; system++) {
            double x = (originX << 4) + random.nextInt(16);
            double y = startY(random);
            double z = (originZ << 4) + random.nextInt(16);
            int tunnels = 1;
            if (random.nextInt(4) == 0) {
                tunnel(target, random.nextLong(), x, y, z, 1.0F + random.nextFloat() * 6.0F, 0.0F, 0.0F, -1, -1, 0.5,
                        open, false);
                tunnels += random.nextInt(4);
            }
            for (int tunnel = 0; tunnel < tunnels; tunnel++) {
                float yaw = random.nextFloat() * (float) Math.PI * 2.0F;
                float pitch = (random.nextFloat() - 0.5F) * 2.0F / 8.0F;
                float width = random.nextFloat() * 2.0F + random.nextFloat();
                if (random.nextInt(10) == 0) {
                    width *= random.nextFloat() * random.nextFloat() * 3.0F + 1.0F;
                }
                tunnel(target, random.nextLong(), x, y, z, width, yaw, pitch, 0, 0, 1.0, open, false);
            }
        }
    }

    /**
     * Vanilla 1.16's canyon: started 20 to 67 blocks above the cave band's bottom, wide, three times
     * as tall as it is wide, never forking, and open to the sky where it climbs that far.
     */
    private void ravine(Target target, SplittableRandom random, int originX, int originZ) {
        double x = (originX << 4) + random.nextInt(16);
        double y = vanillaBottom + random.nextInt(random.nextInt(40) + 8) + 20;
        double z = (originZ << 4) + random.nextInt(16);
        float yaw = random.nextFloat() * (float) Math.PI * 2.0F;
        float pitch = (random.nextFloat() - 0.5F) * 2.0F / 8.0F;
        float width = (random.nextFloat() * 2.0F + random.nextFloat()) * 2.0F;
        tunnel(target, random.nextLong(), x, y, z, width, yaw, pitch, 0, 0, 3.0, true, true);
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
                        float pitch, int step, int maxSteps, double yScale, boolean open, boolean ravine) {
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
            if (!room && !ravine && step == split && width > 1.0F) {
                tunnel(target, random.nextLong(), x, y, z, random.nextFloat() * 0.5F + 0.5F,
                        yaw - (float) (Math.PI / 2), pitch / 3.0F, step, maxSteps, 1.0, open, false);
                tunnel(target, random.nextLong(), x, y, z, random.nextFloat() * 0.5F + 0.5F,
                        yaw + (float) (Math.PI / 2), pitch / 3.0F, step, maxSteps, 1.0, open, false);
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
                    target.sphere(x, y, z, radiusH, radiusV, open);
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
        /** As {@link #ceilings} for an open cave: the surface itself, unless water is near. */
        final int[] openCeilings;
        final int highest;

        private Target(int minX, int minZ, UndergroundCells cells, int[] ceilings, int[] openCeilings,
                       int highest) {
            this.minX = minX;
            this.minZ = minZ;
            this.cells = cells;
            this.ceilings = ceilings;
            this.openCeilings = openCeilings;
            this.highest = highest;
        }

        static Target of(int chunkX, int chunkZ, UndergroundCells cells, ChunkSampler.ChunkSamples samples) {
            if (cells.topY() <= cells.floorY()) {
                return null;
            }
            int margin = Math.min(WATER_MARGIN, samples.reach());
            int[] ceilings = new int[256];
            int[] openCeilings = new int[256];
            int highest = Integer.MIN_VALUE;
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    int ceiling = samples.surfaceY(localX, localZ) - ROOF;
                    int open = samples.isWater(localX, localZ) ? ceiling : samples.surfaceY(localX, localZ);
                    for (int dz = -margin; dz <= margin; dz++) {
                        for (int dx = -margin; dx <= margin; dx++) {
                            if (samples.isWater(localX + dx, localZ + dz)) {
                                int underWater = samples.surfaceY(localX + dx, localZ + dz) - ROOF - WATER_MARGIN;
                                ceiling = Math.min(ceiling, underWater);
                                open = Math.min(open, underWater);
                            }
                        }
                    }
                    ceiling = Math.min(ceiling, cells.topY() - 1);
                    open = Math.min(open, cells.topY() - 1);
                    ceilings[localZ << 4 | localX] = ceiling;
                    openCeilings[localZ << 4 | localX] = open;
                    highest = Math.max(highest, Math.max(ceiling, open));
                }
            }
            if (highest < cells.floorY()) {
                return null;
            }
            return new Target(chunkX << 4, chunkZ << 4, cells, ceilings, openCeilings, highest);
        }

        void sphere(double x, double y, double z, double radiusH, double radiusV, boolean open) {
            int[] tops = open ? openCeilings : ceilings;
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
                    int ceiling = Math.min(toY, tops[localZ << 4 | localX]);
                    for (int blockY = fromY; blockY <= ceiling; blockY++) {
                        double dy = (blockY + 0.5 - y) / radiusV;
                        // Vanilla's flat floor: the bottom of each sphere is left in place.
                        if (dy > -0.7 && horizontal + dy * dy < 1.0
                                && (open ? UndergroundCells.carvableOpen(cells.get(localX, blockY, localZ))
                                        : UndergroundCells.carvable(cells.get(localX, blockY, localZ)))) {
                            cells.set(localX, blockY, localZ, UndergroundCells.CARVED);
                        }
                    }
                }
            }
        }
    }
}
