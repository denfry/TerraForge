package dev.terraforge.core.coord;

import dev.terraforge.core.projection.Projection;

/**
 * The rectangle of Minecraft blocks the planet occupies, both corners inclusive.
 *
 * <p>The projection itself never ends: longitude wraps, so east of the antimeridian the Earth
 * simply starts again, and latitude clamps, so past a pole the polar row repeats forever. This is
 * the one copy of the planet between those seams -- longitude -180..180 and latitude up to the
 * projection's {@link Projection#maxLatitude()} -- and everything outside it is a duplicate or a
 * smear, never new geography.
 */
public record WorldExtent(int minX, int minZ, int maxX, int maxZ) {

    private static final int CHUNK_SHIFT = 4;

    public WorldExtent {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("empty world extent: x " + minX + ".." + maxX
                    + ", z " + minZ + ".." + maxZ);
        }
    }

    /** The whole planet under the transformer's projection, origin and scale. */
    public static WorldExtent ofPlanet(CoordinateTransformer transformer) {
        Projection projection = transformer.projection();
        double originLatitude = transformer.origin().latitude();
        double originLongitude = transformer.origin().longitude();

        // +180 normalises to -180, so the eastern edge cannot be asked for directly: it is the
        // western edge plus one full turn of the projected parallel.
        double west = transformer.toMinecraft(originLatitude, -180.0).x();
        double turn = 2.0 * Math.abs(projection.toPlane(originLatitude, -180.0).east())
                / transformer.metersPerBlock();
        double north = transformer.toMinecraft(projection.maxLatitude(), originLongitude).z();
        double south = transformer.toMinecraft(-projection.maxLatitude(), originLongitude).z();

        // Rounded inward: a block is inside only when all of it is on the planet.
        return new WorldExtent(
                (int) Math.ceil(west), (int) Math.ceil(north),
                (int) Math.floor(west + turn) - 1, (int) Math.floor(south) - 1);
    }

    public boolean contains(double x, double z) {
        return x >= minX && x < maxX + 1.0 && z >= minZ && z < maxZ + 1.0;
    }

    /** True when any block of the chunk is inside; an edge chunk is generated whole. */
    public boolean intersectsChunk(int chunkX, int chunkZ) {
        return chunkX >= (minX >> CHUNK_SHIFT) && chunkX <= (maxX >> CHUNK_SHIFT)
                && chunkZ >= (minZ >> CHUNK_SHIFT) && chunkZ <= (maxZ >> CHUNK_SHIFT);
    }

    /** Nearest X still inside, kept {@code inset} blocks off the edge so a player is not pinned on it. */
    public double clampX(double x, double inset) {
        return Math.clamp(x, Math.min(minX + inset, centerX()), Math.max(maxX + 1.0 - inset, centerX()));
    }

    public double clampZ(double z, double inset) {
        return Math.clamp(z, Math.min(minZ + inset, centerZ()), Math.max(maxZ + 1.0 - inset, centerZ()));
    }

    public double centerX() {
        return (minX + maxX + 1.0) / 2.0;
    }

    public double centerZ() {
        return (minZ + maxZ + 1.0) / 2.0;
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }
}
