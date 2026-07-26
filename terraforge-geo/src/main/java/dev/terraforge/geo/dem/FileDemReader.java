package dev.terraforge.geo.dem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads {@code .tfdem} tiles from a directory laid out by {@code terraforge prepare-dem}.
 *
 * <p>The directory is scanned once at construction so that a missing tile costs a map lookup rather
 * than a filesystem stat on every chunk. Adding tiles to a running server therefore needs a
 * {@code /earth reload}; that is the intended trade -- generation threads must not touch the disk
 * to answer "is there data here".
 */
public final class FileDemReader implements DemReader {

    private static final String EXTENSION = ".tfdem";

    private final Path directory;
    /** Ordered for stable reporting, a set because {@link #exists} is on the per-chunk path. */
    private final Set<DemTileKey> available;

    public FileDemReader(Path directory) throws IOException {
        this.directory = directory;
        this.available = scan(directory);
    }

    private static Set<DemTileKey> scan(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }
        List<DemTileKey> keys = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(EXTENSION))
                    .forEach(name -> parseKey(name).ifPresent(keys::add));
        }
        keys.sort((a, b) -> a.latDegree() != b.latDegree()
                ? Integer.compare(a.latDegree(), b.latDegree())
                : Integer.compare(a.lonDegree(), b.lonDegree()));
        return Collections.unmodifiableSet(new LinkedHashSet<>(keys));
    }

    /** Parses {@code N50E008.tfdem}; anything else in the directory is ignored rather than fatal. */
    static Optional<DemTileKey> parseKey(String fileName) {
        String base = fileName.substring(0, fileName.length() - EXTENSION.length());
        if (base.length() != 7) {
            return Optional.empty();
        }
        char latHemisphere = Character.toUpperCase(base.charAt(0));
        char lonHemisphere = Character.toUpperCase(base.charAt(3));
        if ((latHemisphere != 'N' && latHemisphere != 'S')
                || (lonHemisphere != 'E' && lonHemisphere != 'W')) {
            return Optional.empty();
        }
        try {
            int lat = Integer.parseInt(base, 1, 3, 10);
            int lon = Integer.parseInt(base, 4, 7, 10);
            if (lat > 90 || lon > 180) {
                return Optional.empty();
            }
            return Optional.of(new DemTileKey(
                    latHemisphere == 'S' ? -lat : lat,
                    lonHemisphere == 'W' ? -lon : lon));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public Path directory() {
        return directory;
    }

    @Override
    public Optional<DemTile> read(DemTileKey key) throws IOException {
        Path file = directory.resolve(key.fileName());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(MappedDemTile.map(file));
    }

    @Override
    public boolean exists(DemTileKey key) {
        return available.contains(key);
    }

    @Override
    public Iterable<DemTileKey> availableTiles() {
        return available;
    }

    public int tileCount() {
        return available.size();
    }

    @Override
    public void close() {
        // Mappings outlive their channel; nothing to release here.
    }
}
