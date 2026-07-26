package dev.terraforge.geo.dem;

import java.io.IOException;
import java.util.Optional;

/**
 * Reads prepared {@code .tfdem} tiles from disk.
 *
 * <p>Runtime never parses GeoTIFF: the CLI transcodes source rasters into {@code .tfdem} offline
 * (see docs/dem.md for the on-disk format). This keeps the server free of a heavyweight GIS stack
 * and makes tile loading a memory-map instead of a decode.
 */
public interface DemReader extends AutoCloseable {

    /** Loads a tile, or empty when no prepared file exists for that key. */
    Optional<DemTile> read(DemTileKey key) throws IOException;

    /** True when a prepared file exists, without loading it. */
    boolean exists(DemTileKey key);

    /** Every tile available in the data directory; used for coverage reporting and pregeneration. */
    Iterable<DemTileKey> availableTiles();

    @Override
    void close() throws IOException;
}
