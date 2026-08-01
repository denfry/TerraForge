package dev.terraforge.cli.fetch;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which files the publisher does not have, so a re-run does not ask again.
 *
 * <p>A source directory is already a cache of what was downloaded. This is the other half of it: a
 * cache of what could not be. Roughly 60% of the planet's degree cells are open ocean and Copernicus
 * publishes no tile for any of them, so a whole-world fetch discovers about 38,000 permanent 404s.
 * Without this, every subsequent run -- and re-running is the normal way to finish an interrupted
 * download -- pays for all 38,000 requests again before it can start transferring anything.
 *
 * <p>Only genuine "not published" answers are recorded. A timeout, a 500 or a dropped connection is
 * a failure, not an absence, and is retried on the next run as before: the distinction is the whole
 * point, because writing off a tile the mirror was merely having a bad minute about would silently
 * put a hole in the world.
 *
 * <p>Entries are appended as they are found rather than written at the end, so an interrupted run
 * keeps everything it learned.
 */
public final class AbsentRegistry {

    /** Lives in the source root, beside the dataset directories it describes. */
    public static final String FILE_NAME = ".terraforge-absent";

    private static final String HEADER =
            "# Files the publisher does not have -- almost always ocean-only tiles."
            + System.lineSeparator()
            + "# Delete this file, or pass --replace, to ask about them again."
            + System.lineSeparator();

    private final Path file;
    private final Set<String> absent;
    private final boolean writable;

    private AbsentRegistry(Path file, Set<String> absent, boolean writable) {
        this.file = file;
        this.absent = absent;
        this.writable = writable;
    }

    /** A registry that remembers nothing and records nothing, for {@code --replace} runs. */
    public static AbsentRegistry disabled() {
        return new AbsentRegistry(null, ConcurrentHashMap.newKeySet(), false);
    }

    /**
     * Loads the registry from {@code sourceRoot}, creating nothing until something is recorded.
     *
     * <p>An unreadable registry is not an error: the worst it costs is the requests it would have
     * saved, so it degrades to {@link #disabled()} rather than stopping a download.
     */
    public static AbsentRegistry open(Path sourceRoot) {
        Path file = sourceRoot.resolve(FILE_NAME);
        Set<String> absent = ConcurrentHashMap.newKeySet();
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String entry = line.strip();
                    if (!entry.isEmpty() && !entry.startsWith("#")) {
                        absent.add(entry);
                    }
                }
            } catch (IOException exception) {
                return disabled();
            }
        }
        return new AbsentRegistry(file, absent, true);
    }

    /** Whether {@code download} is known to be unpublished. */
    public boolean contains(SourceCatalog.Download download) {
        return absent.contains(key(download));
    }

    /** Records {@code download} as unpublished. Recording the same file twice writes one line. */
    public void record(SourceCatalog.Download download) {
        if (!writable || !absent.add(key(download))) {
            return;
        }
        append(key(download));
    }

    /** How many absences are already known; reported so the count is never a silent saving. */
    public int size() {
        return absent.size();
    }

    private synchronized void append(String entry) {
        try {
            boolean fresh = !Files.isRegularFile(file);
            Path parent = file.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) {
                    writer.write(HEADER);
                }
                writer.write(entry);
                writer.newLine();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot update " + file, exception);
        }
    }

    private static String key(SourceCatalog.Download download) {
        return download.dataset() + "/" + download.fileName();
    }
}
