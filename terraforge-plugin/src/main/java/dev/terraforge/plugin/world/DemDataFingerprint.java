package dev.terraforge.plugin.world;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SHA-256 identity of a data directory (the prepared DEM/GIS files a managed world was staged
 * against), used as the data fingerprint recorded into and verified against
 * {@code ManagedWorldManifest}.
 *
 * <p>Deliberately hashes sorted relative paths and file sizes rather than full file contents: DEM
 * tiles and other geo datasets can be many gigabytes, and path+size is a proportionate integrity
 * proxy for detecting that the prepared data changed, without re-reading it all on every enable.
 * A directory that does not exist (no DEM prepared yet) fingerprints as the empty listing rather
 * than throwing, so verification can still run against a manifest staged before any DEM existed.
 */
public final class DemDataFingerprint {
    private DemDataFingerprint() {}

    public static String of(Path directory) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String entry : sortedRelativePathsWithSizes(directory)) {
                digest.update(entry.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static List<String> sortedRelativePathsWithSizes(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .map(file -> relativeEntry(directory, file))
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read data directory " + directory, exception);
        }
    }

    private static String relativeEntry(Path directory, Path file) {
        String relativePath = directory.relativize(file).toString().replace('\\', '/');
        long size;
        try {
            size = Files.size(file);
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read size of " + file, exception);
        }
        return relativePath + "=" + size;
    }
}
