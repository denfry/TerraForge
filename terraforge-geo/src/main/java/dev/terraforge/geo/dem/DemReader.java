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

    /**
     * True when the prepared tiles carry real ocean depth rather than stopping at the coast.
     *
     * <p>A property of preparation, not of the running server: bathymetry has to be merged into the
     * source raster before transcoding, which is why the answer comes from the reader and not from
     * inspecting samples at runtime.
     */
    boolean hasBathymetry();

    /**
     * Bytes one prepared tile occupies, or 0 when nothing is prepared.
     *
     * <p>Known from the tile headers at startup, and used to turn an entry-based cache limit into a
     * memory-based one -- counting tiles says nothing about memory when a tile can be 50 kB or
     * 50 MB depending on preparation.
     */
    long typicalTileBytes();

    @Override
    void close() throws IOException;
}
