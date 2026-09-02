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

    /**
     * Sum and count of this tile's samples inside a geographic box, for footprint averaging.
     *
     * <p>Sum and count rather than a mean, because a footprint may straddle a tile border and the
     * caller has to combine several tiles' contributions without weighting a two-sample sliver the
     * same as a hundred-sample interior.
     *
     * <p>The box is clipped to this tile, and the tile's east and south edges are deliberately
     * excluded: {@code .tfdem} tiles are edge-inclusive in the SRTM style, so those rows duplicate
     * the neighbouring tile's west and north edges and counting both would weight one line of
     * ground twice.
     *
     * @param maxSamplesPerAxis upper bound on samples read per axis; larger boxes are strided so a
     *                          coarse {@code blocks-per-km} cannot turn one column into a
     *                          megabyte-scale scan. Must be at least 1.
     */
    SampleTotal averageWithin(double minLatitude, double minLongitude,
                              double maxLatitude, double maxLongitude, int maxSamplesPerAxis);

    /** Partial footprint aggregate from one tile. */
    record SampleTotal(double sum, int count) {

        /** Nothing of this tile lay inside the box, or every sample in it was a void. */
        public static final SampleTotal EMPTY = new SampleTotal(0.0, 0);
    }

    /** Bytes this tile occupies, for cache accounting. */
    long sizeBytes();
}
