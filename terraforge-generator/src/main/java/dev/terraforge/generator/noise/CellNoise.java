package dev.terraforge.generator.noise;

/**
 * Deterministic, seedless value noise over world block coordinates.
 *
 * <p>Everything TerraForge places is a function of the data and the config, never of the world
 * seed, so two servers with the same inputs generate the same world. This class is how spatial
 * variety is added without breaking that promise: a hash of the coordinates, a cell index and a
 * salt gives a uniform value in {@code [0, 1)} that is the same on every server and every run.
 *
 * <p>Two shapes are offered. {@link #cell} is constant across a square cell and jumps at its edge,
 * which is what a one-crop field or a single-species flower patch needs. {@link #smooth} is bilinear
 * between cell corners, so a meadow's flower density rises and falls gradually rather than in tiles.
 *
 * <p>Immutable, thread-safe, allocation-free.
 */
public final class CellNoise {

    private CellNoise() {
    }

    /** A well-mixed 64-bit hash of three integers. */
    public static long hash(long a, long b, long salt) {
        long h = a * 0x9E3779B97F4A7C15L ^ (b + 0x632BE59BD9B4E019L) * 0xC2B2AE3D27D4EB4FL ^ salt;
        h ^= h >>> 32;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        return h;
    }

    /** A uniform value in {@code [0, 1)} for one exact block column. */
    public static double at(int x, int z, long salt) {
        return unit(hash(x, z, salt));
    }

    /**
     * A uniform value in {@code [0, 1)} that is constant over each {@code size}-block square cell.
     *
     * @param size cell edge in blocks, at least 1
     */
    public static double cell(int x, int z, int size, long salt) {
        return unit(hash(Math.floorDiv(x, size), Math.floorDiv(z, size), salt));
    }

    /** The cell index a column belongs to, for callers that hash further state from it. */
    public static long cellId(int x, int z, int size, long salt) {
        return hash(Math.floorDiv(x, size), Math.floorDiv(z, size), salt);
    }

    /**
     * Bilinearly interpolated value noise in {@code [0, 1)} with lattice spacing {@code size}.
     * Smooth across cell edges, so it makes gradients rather than tiles.
     */
    public static double smooth(int x, int z, int size, long salt) {
        int cx = Math.floorDiv(x, size);
        int cz = Math.floorDiv(z, size);
        double fx = fade((x - cx * (double) size) / size);
        double fz = fade((z - cz * (double) size) / size);
        double v00 = unit(hash(cx, cz, salt));
        double v10 = unit(hash(cx + 1, cz, salt));
        double v01 = unit(hash(cx, cz + 1, salt));
        double v11 = unit(hash(cx + 1, cz + 1, salt));
        double top = v00 + (v10 - v00) * fx;
        double bottom = v01 + (v11 - v01) * fx;
        return top + (bottom - top) * fz;
    }

    private static double fade(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
