package dev.terraforge.cli.dem;

import dev.terraforge.geo.dem.DemTileKey;
import java.io.IOException;

/**
 * One source raster being transcoded into a {@code .tfdem} tile.
 *
 * <p>Row-at-a-time by design: preparation must run in constant memory whatever the source format
 * or tile size, so no implementation is allowed to require the whole grid in RAM.
 *
 * <p>Implementations live in the CLI only. Nothing that reads a source raster format is ever loaded
 * by the server.
 */
public interface DemSource extends AutoCloseable {

    /** The one-degree cell this raster covers. */
    DemTileKey key();

    int width();

    int height();

    /**
     * One row of elevations in metres, west to east, with row 0 at the north edge.
     *
     * <p>Voids are returned as {@link dev.terraforge.core.data.ElevationProvider#NO_DATA}.
     *
     * <p>The array may be reused between calls -- consume it before asking for the next row.
     */
    double[] readRow(int y) throws IOException;

    /** True when the source carries sub-metre values that need float32 preservation. */
    default boolean needsFloat32() {
        return false;
    }

    /** True when at least one emitted sample comes from GEBCO bathymetry. */
    default boolean containsBathymetry() throws IOException {
        return false;
    }

    @Override
    void close() throws IOException;
}
