package dev.terraforge.core.projection;

/**
 * A projected planar coordinate in metres.
 *
 * @param east  metres east of the projection origin
 * @param north metres north of the projection origin
 */
public record PlanePoint(double east, double north) {

    public PlanePoint minus(PlanePoint other) {
        return new PlanePoint(east - other.east, north - other.north);
    }

    public PlanePoint plus(PlanePoint other) {
        return new PlanePoint(east + other.east, north + other.north);
    }
}
