package dev.terraforge.core.coord;

import dev.terraforge.core.projection.PlanePoint;
import dev.terraforge.core.projection.Projection;

/**
 * The single place where geography becomes Minecraft coordinates and back.
 *
 * <pre>
 *   lat/lon --[projection]--> plane metres --[scale + origin]--> Minecraft X/Z
 * </pre>
 *
 * <p>Immutable and thread-safe, so chunk generation threads can share one instance. The world
 * origin is defined so that the configured {@code earth.origin} maps exactly to Minecraft (0, 0).
 *
 * <p>Sign convention: Minecraft Z grows south, whereas projected northing grows north, hence the
 * negation of the north axis.
 */
public final class CoordinateTransformer {

    private static final double METERS_PER_KM = 1000.0;

    private final Projection projection;
    private final GeoPoint origin;
    private final double blocksPerKm;
    private final PlanePoint originPlane;

    public CoordinateTransformer(Projection projection, GeoPoint origin, double blocksPerKm) {
        if (blocksPerKm <= 0.0 || !Double.isFinite(blocksPerKm)) {
            throw new IllegalArgumentException("blocks-per-km must be positive and finite: " + blocksPerKm);
        }
        this.projection = projection;
        this.origin = origin;
        this.blocksPerKm = blocksPerKm;
        this.originPlane = projection.toPlane(origin);
    }

    public Projection projection() {
        return projection;
    }

    public GeoPoint origin() {
        return origin;
    }

    public double blocksPerKm() {
        return blocksPerKm;
    }

    /** How many plane metres one block covers. At 1 block/km this is 1000. */
    public double metersPerBlock() {
        return METERS_PER_KM / blocksPerKm;
    }

    // --- geo -> minecraft ---------------------------------------------------

    /** Exact (non-rounded) Minecraft position; the generator needs sub-block precision. */
    public MinecraftPos toMinecraft(double latitude, double longitude) {
        PlanePoint plane = projection.toPlane(latitude, longitude);
        double dx = plane.east() - originPlane.east();
        double dz = originPlane.north() - plane.north();
        double metersPerBlock = metersPerBlock();
        return new MinecraftPos(dx / metersPerBlock, dz / metersPerBlock);
    }

    public MinecraftPos toMinecraft(GeoPoint point) {
        return toMinecraft(point.latitude(), point.longitude());
    }

    // --- minecraft -> geo ---------------------------------------------------

    public GeoPoint toGeographic(double blockX, double blockZ) {
        double metersPerBlock = metersPerBlock();
        double east = originPlane.east() + blockX * metersPerBlock;
        double north = originPlane.north() - blockZ * metersPerBlock;
        return projection.toGeographic(east, north);
    }

    /**
     * Geographic bounds covered by a Minecraft chunk.
     *
     * <p>Computed from the chunk corners; because every supported projection is monotonic in both
     * axes, corner sampling is exact rather than an approximation.
     */
    public GeoBounds chunkBounds(int chunkX, int chunkZ) {
        double minX = chunkX * 16.0;
        double minZ = chunkZ * 16.0;
        GeoPoint northWest = toGeographic(minX, minZ);
        GeoPoint southEast = toGeographic(minX + 16.0, minZ + 16.0);
        return GeoBounds.of(northWest, southEast);
    }

    /** Ground metres represented by one block at the given latitude, distortion included. */
    public double groundMetersPerBlock(double latitude) {
        return metersPerBlock() / projection.scaleFactor(latitude);
    }

    @Override
    public String toString() {
        return "CoordinateTransformer{projection=" + projection.id()
                + ", origin=" + origin + ", blocksPerKm=" + blocksPerKm + '}';
    }
}
