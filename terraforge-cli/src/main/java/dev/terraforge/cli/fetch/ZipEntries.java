package dev.terraforge.cli.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    /**
     * Extracts every entry whose name ends with {@code extension} into {@code directory}.
     *
     * <p>For archives whose internal layout is not a published contract. WOKAM ships several layers
     * and has renamed them between editions, so naming one entry would break on the next release,
     * silently and a year later. Only the entry's base name is used -- never the path it carries --
     * so this is no more exposed to zip-slip than {@link #extract}.
     *
     * @return the file names written, skipping those already present
     */
    public static List<String> extractAll(Path archive, String extension, Path directory,
                                          boolean replace) throws IOException {
        Files.createDirectories(directory);
        List<String> written = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().toLowerCase(Locale.ROOT).endsWith(extension))
                    .toList();
            for (ZipEntry entry : entries) {
                Path name = Path.of(entry.getName().replace('\\', '/')).getFileName();
                if (name == null) {
                    continue;
                }
                Path target = directory.resolve(name.toString());
                if (!replace && Files.isRegularFile(target) && Files.size(target) > 0) {
                    continue;
                }
                Path partial = target.resolveSibling(name + ".part");
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
                written.add(name.toString());
            }
        }
        return written;
    }

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
