package dev.terraforge.generator.underground;

import dev.terraforge.generator.noise.CellNoise;
import dev.terraforge.generator.pipeline.ChunkSampler;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Places ore veins and stone pockets, vanilla 1.16's blob shape, into one chunk's cells.
 *
 * <p>A vein belongs to the chunk it starts in, but its blocks may spill a few blocks into the
 * neighbours. So every chunk replays the veins of the eight chunks around it as well as its own, each
 * from the same seed, and keeps the blocks that land inside itself: the two halves of a vein on a
 * chunk border are placed by two different chunks and still meet exactly.
 */
final class VeinPlacer {

    private static final long SALT = 0x6F72655F7665696EL;

    private final List<OreBand> bands;
    private final double multiplier;

    VeinPlacer(List<OreBand> bands, double multiplier) {
        this.bands = bands;
        this.multiplier = Math.max(0.0, multiplier);
    }

    void place(int chunkX, int chunkZ, UndergroundCells cells, ChunkSampler.ChunkSamples samples) {
        if (cells.topY() <= cells.floorY()) {
            return;
        }
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        for (OreBand band : bands) {
            int reach = band.reach();
            if (band.minY() - reach >= cells.topY() || band.maxY() + reach < cells.floorY()) {
                continue;
            }
            byte code = UndergroundCells.code(band.mineral());
            long salt = SALT ^ band.name().hashCode() * 0x9E3779B97F4A7C15L;
            // A single-block vein cannot spill over: only this chunk's own starts matter.
            int span = band.veinSize() == 1 ? 0 : 1;
            for (int originZ = chunkZ - span; originZ <= chunkZ + span; originZ++) {
                for (int originX = chunkX - span; originX <= chunkX + span; originX++) {
                    SplittableRandom random = new SplittableRandom(CellNoise.hash(originX, originZ, salt));
                    int attempts = band.attempts(random, multiplier);
                    for (int attempt = 0; attempt < attempts; attempt++) {
                        int x = (originX << 4) + random.nextInt(16);
                        int z = (originZ << 4) + random.nextInt(16);
                        int y = band.sampleY(random);
                        long veinSeed = random.nextLong();
                        if (x + reach < minX || x - reach >= minX + 16 || z + reach < minZ || z - reach >= minZ + 16
                                || y + reach < cells.floorY() || y - reach >= cells.topY()) {
                            continue;
                        }
                        if (band.needsElevation() && !highEnough(band, samples, x - minX, z - minZ)) {
                            continue;
                        }
                        if (band.veinSize() == 1) {
                            replace(cells, code, x - minX, y, z - minZ);
                        } else {
                            vein(cells, code, band.veinSize(), x - minX, y, z - minZ, new SplittableRandom(veinSeed));
                        }
                    }
                }
            }
        }
    }

    /** Whether the real ground over this column is high enough; only own-chunk columns are known. */
    private static boolean highEnough(OreBand band, ChunkSampler.ChunkSamples samples, int localX, int localZ) {
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) {
            return false;
        }
        return samples.at(localX, localZ).elevationMeters() >= band.minElevationMetres();
    }

    /** Vanilla's ore blob: spheres of varying girth strung along a short random segment. */
    private static void vein(UndergroundCells cells, byte code, int size, int x, int y, int z,
                             SplittableRandom random) {
        double angle = random.nextDouble() * Math.PI;
        double spread = size / 8.0;
        double x0 = x + Math.sin(angle) * spread;
        double x1 = x - Math.sin(angle) * spread;
        double z0 = z + Math.cos(angle) * spread;
        double z1 = z - Math.cos(angle) * spread;
        double y0 = y + random.nextInt(3) - 2;
        double y1 = y + random.nextInt(3) - 2;
        for (int step = 0; step < size; step++) {
            double t = (double) step / size;
            double centreX = x0 + (x1 - x0) * t;
            double centreY = y0 + (y1 - y0) * t;
            double centreZ = z0 + (z1 - z0) * t;
            double girth = random.nextDouble() * size / 16.0;
            double radius = ((Math.sin(Math.PI * t) + 1.0) * girth + 1.0) / 2.0;
            int fromX = Math.max(0, (int) Math.floor(centreX - radius));
            int toX = Math.min(15, (int) Math.floor(centreX + radius));
            int fromZ = Math.max(0, (int) Math.floor(centreZ - radius));
            int toZ = Math.min(15, (int) Math.floor(centreZ + radius));
            int fromY = (int) Math.floor(centreY - radius);
            int toY = (int) Math.floor(centreY + radius);
            for (int blockX = fromX; blockX <= toX; blockX++) {
                double dx = (blockX + 0.5 - centreX) / radius;
                if (dx * dx >= 1.0) {
                    continue;
                }
                for (int blockY = fromY; blockY <= toY; blockY++) {
                    double dy = (blockY + 0.5 - centreY) / radius;
                    if (dx * dx + dy * dy >= 1.0) {
                        continue;
                    }
                    for (int blockZ = fromZ; blockZ <= toZ; blockZ++) {
                        double dz = (blockZ + 0.5 - centreZ) / radius;
                        if (dx * dx + dy * dy + dz * dz < 1.0) {
                            replace(cells, code, blockX, blockY, blockZ);
                        }
                    }
                }
            }
        }
    }

    private static void replace(UndergroundCells cells, byte code, int localX, int y, int localZ) {
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) {
            return;
        }
        if (UndergroundCells.hostsMineral(cells.get(localX, y, localZ))) {
            cells.set(localX, y, localZ, code);
        }
    }
}
