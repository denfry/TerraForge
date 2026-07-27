package dev.terraforge.geo.dem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads {@code .tfdem} tiles from a prepared data directory.
 *
 * <p>The directory is catalogued once at construction -- 64 header bytes per file -- so coverage
 * questions ("is this point prepared?", "which tiles exist?") are answered from memory instead of
 * hitting the filesystem on every chunk. Sample data is mapped lazily, on first read of a tile.
 *
 * <p>Files that are not readable tiles are reported and skipped: one corrupt file must not stop a
 * server from generating the rest of the world.
 */
public final class FileDemReader implements DemReader {

    private static final System.Logger LOG = System.getLogger("TerraForge-DEM");

    private final Path directory;
    private final Map<DemTileKey, Path> catalogue;
    private final boolean bathymetry;
    private final long typicalTileBytes;
    private volatile boolean closed;

    private FileDemReader(Path directory, Map<DemTileKey, Path> catalogue,
                          boolean bathymetry, long typicalTileBytes) {
        this.directory = directory;
        this.catalogue = catalogue;
        this.bathymetry = bathymetry;
        this.typicalTileBytes = typicalTileBytes;
    }

    /**
     * Catalogues {@code directory}. A missing directory is not an error -- it yields an empty
     * reader, and the terrain stage falls back to {@code terrain.fallback-elevation}.
     */
    public static FileDemReader open(Path directory) throws IOException {
        Map<DemTileKey, Path> catalogue = new TreeMap<>(
                java.util.Comparator.comparingInt(DemTileKey::latDegree)
                        .thenComparingInt(DemTileKey::lonDegree));
        boolean bathymetry = false;
        long largestTileBytes = 0;

        if (Files.isDirectory(directory)) {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".tfdem")).toList()) {
                    TfDemHeader header = readHeader(file);
                    if (header == null) {
                        continue;
                    }
                    DemTileKey nameKey;
                    try {
                        nameKey = DemTileKey.parse(file.getFileName().toString());
                    } catch (IllegalArgumentException e) {
                        LOG.log(System.Logger.Level.WARNING,
                                "Skipping DEM tile with unrecognised name: {0}", file.getFileName());
                        continue;
                    }
                    if (!nameKey.equals(header.key())) {
                        LOG.log(System.Logger.Level.WARNING,
                                "Skipping DEM tile {0}: header says it covers {1}",
                                file.getFileName(), header.key());
                        continue;
                    }
                    catalogue.put(nameKey, file);
                    // Only float32 preparation carries merged bathymetry; int16 tiles stop at the coast.
                    bathymetry |= header.encoding() == TfDemFormat.ENCODING_FLOAT32;
                    // Largest, not mean: the cache bound must hold for the heaviest tile, and a
                    // directory usually has one grid size anyway.
                    largestTileBytes = Math.max(largestTileBytes, header.expectedFileSize());
                }
            }
        }
        LOG.log(System.Logger.Level.INFO, "DEM directory {0}: {1} prepared tiles{2}",
                directory, catalogue.size(), bathymetry ? " (with bathymetry)" : "");
        return new FileDemReader(directory, Collections.unmodifiableMap(catalogue), bathymetry, largestTileBytes);
    }

    private static TfDemHeader readHeader(Path file) {
        try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            var buffer = java.nio.ByteBuffer.allocate(TfDemFormat.HEADER_BYTES);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // keep reading
            }
            return TfDemHeader.parse(buffer.flip());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Skipping unreadable DEM tile " + file + ": " + e.getMessage());
            return null;
        }
    }

    public Path directory() {
        return directory;
    }

    @Override
    public Optional<DemTile> read(DemTileKey key) throws IOException {
        ensureOpen();
        Path file = catalogue.get(key);
        if (file == null) {
            return Optional.empty();
        }
        return Optional.of(MappedDemTile.open(file));
    }

    @Override
    public boolean exists(DemTileKey key) {
        return catalogue.containsKey(key);
    }

    @Override
    public Iterable<DemTileKey> availableTiles() {
        return catalogue.keySet();
    }

    @Override
    public boolean hasBathymetry() {
        return bathymetry;
    }

    @Override
    public long typicalTileBytes() {
        return typicalTileBytes;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("DEM reader is closed");
        }
    }

    /**
     * Marks the reader closed. Tiles already handed out stay valid until they are unreachable --
     * Java has no portable way to unmap a buffer, so lifetime is left to the collector.
     */
    @Override
    public void close() {
        closed = true;
    }

    /** Convenience for callers that cannot handle a checked exception on a hot path. */
    public Optional<DemTile> readUnchecked(DemTileKey key) {
        try {
            return read(key);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to map DEM tile " + key, e);
        }
    }
}
