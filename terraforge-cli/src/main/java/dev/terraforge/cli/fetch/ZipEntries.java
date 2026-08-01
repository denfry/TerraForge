package dev.terraforge.cli.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pulls one named file out of a downloaded archive.
 *
 * <p>Deliberately not a general "unzip here": the GeoNames archives also carry a {@code readme.txt},
 * and extracting it into {@code cities/} would hand {@code prepare-region} a text file that is not a
 * gazetteer. Naming the entry keeps the source tree free of anything the importers would choke on,
 * and sidesteps zip-slip entirely -- the destination is chosen by the caller, never by the archive.
 */
public final class ZipEntries {

    private ZipEntries() {
    }

    /**
     * Extracts {@code entryName} from {@code archive} to {@code target}.
     *
     * @return {@code true} when the file was written, {@code false} when the target already existed
     * @throws IOException when the archive does not contain the entry
     */
    public static boolean extract(Path archive, String entryName, Path target, boolean replace)
            throws IOException {
        if (!replace && Files.isRegularFile(target) && Files.size(target) > 0) {
            return false;
        }
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                entry = zip.stream().filter(candidate -> !candidate.isDirectory()
                        && candidate.getName().endsWith("/" + entryName)).findFirst().orElse(null);
            }
            if (entry == null || entry.isDirectory()) {
                throw new IOException(archive.getFileName() + " does not contain " + entryName);
            }
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }
}
