package dev.terraforge.generator.underground;

import java.util.SplittableRandom;

/**
 * One kind of vein at one height range: how big, how many per chunk, where.
 *
 * @param name               stable identity; seeds the band's randomness, so adding a band never
 *                           moves the veins of another
 * @param mineral            what the vein is made of
 * @param veinSize           blocks per vein, vanilla's {@code size}; 1 places a single block
 * @param count              mean veins per chunk; the fraction is a probability of one more
 * @param minY               lowest vein centre, inclusive
 * @param maxY               highest vein centre, inclusive
 * @param triangular         centre heights peak mid-range, as vanilla's lapis does, instead of uniform
 * @param minElevationMetres the real ground above must be at least this high, or {@code NaN} for
 *                           anywhere; how emerald stays under mountains
 */
record OreBand(String name, Mineral mineral, int veinSize, double count, int minY, int maxY,
               boolean triangular, double minElevationMetres) {

    OreBand {
        if (veinSize < 1 || count < 0.0 || !Double.isFinite(count) || maxY < minY) {
            throw new IllegalArgumentException("invalid ore band " + name);
        }
    }

    int attempts(SplittableRandom random, double multiplier) {
        double expected = count * multiplier;
        int whole = (int) expected;
        return random.nextDouble() < expected - whole ? whole + 1 : whole;
    }

    int sampleY(SplittableRandom random) {
        int range = maxY - minY;
        if (!triangular) {
            return minY + random.nextInt(range + 1);
        }
        int half = range / 2;
        return minY + random.nextInt(half + 1) + random.nextInt(range - half + 1);
    }

    /** How far a vein's blocks can land from its centre, for deciding whether it can reach a chunk. */
    int reach() {
        return veinSize / 8 + veinSize / 16 + 3;
    }

    boolean needsElevation() {
        return !Double.isNaN(minElevationMetres);
    }
}
