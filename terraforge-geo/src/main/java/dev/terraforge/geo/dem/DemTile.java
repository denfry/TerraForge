package dev.terraforge.geo.dem;

import dev.terraforge.core.coord.GeoBounds;

/**
 * One loaded elevation tile: a regular grid of samples over a 1x1 degree cell.
 *
 * <p>Implementations are immutable after load and safe for concurrent reads, because several chunk
 * generation threads sample the same tile at once. Backing storage is a memory-mapped
 * {@code .tfdem} file, so a resident tile costs address space rather than heap.
 */
public interface DemTile {

    DemTileKey key();

    GeoBounds bounds();

    /** Samples along each axis; a 3601x3601 tile is one arc-second. */
    int width();

    int height();

    /**
     * Raw sample by grid index, with (0,0) at the north-west corner.
     *
     * @return metres above sea level, or {@link dev.terraforge.core.data.ElevationProvider#NO_DATA}
     */
    double sample(int x, int y);

    /**
     * Bilinearly interpolated elevation at an exact geographic point inside {@link #bounds()}.
     *
     * <p>Neighbouring no-data samples are excluded from the interpolation rather than poisoning it;
     * if every neighbour is missing, the result is no-data.
     */
    double interpolate(double latitude, double longitude);

    /** Bytes this tile occupies, for cache accounting. */
    long sizeBytes();
}
