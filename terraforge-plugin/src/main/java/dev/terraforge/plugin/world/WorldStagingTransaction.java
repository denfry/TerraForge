package dev.terraforge.plugin.world;

import dev.terraforge.plugin.io.AtomicFileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Arrays;

/** Applies only preplanned edits and restores exact bytes if any later write fails. */
public final class WorldStagingTransaction {
    /** Records, in commit order, which absolute target path each {@code NNN.bak} backs up. */
    private static final String BACKUP_INDEX_FILE_NAME = "targets.index";

    /** Creates durable original-byte backups before applying a set of edits. */
    public void commit(List<PlannedEdit> edits, Path backupRoot) throws IOException {
        validate(edits);
        StringBuilder index = new StringBuilder();
        for (int i = 0; i < edits.size(); i++) {
            PlannedEdit edit = edits.get(i);
            AtomicFileWriter.write(backupRoot.resolve(String.format("%03d.bak", i)), edit.original());
            index.append(edit.target().toAbsolutePath().normalize()).append('\n');
        }
        AtomicFileWriter.write(backupRoot.resolve(BACKUP_INDEX_FILE_NAME),
                index.toString().getBytes(StandardCharsets.UTF_8));
        commit(edits);
    }

    /**
     * Restores every file backed up by {@link #commit(List, Path)} at {@code backupRoot} to its
     * pre-staging bytes -- or deletes it, when it did not exist before staging -- then removes the
     * backups. A no-op when {@code backupRoot} holds no recognizable backup index, so it is safe to
     * call even when nothing was ever staged there.
     */
    public void restore(Path backupRoot) throws IOException {
        Path indexFile = backupRoot.resolve(BACKUP_INDEX_FILE_NAME);
        if (!Files.isRegularFile(indexFile)) {
            return;
        }
        List<String> targets = Files.readAllLines(indexFile, StandardCharsets.UTF_8);
        for (int i = 0; i < targets.size(); i++) {
            Path target = Path.of(targets.get(i));
            Path backupFile = backupRoot.resolve(String.format("%03d.bak", i));
            byte[] original = Files.isRegularFile(backupFile) ? Files.readAllBytes(backupFile) : new byte[0];
            if (original.length == 0) {
                Files.deleteIfExists(target);
            } else {
                AtomicFileWriter.write(target, original);
            }
        }
        deleteRecursively(backupRoot);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    public void commit(List<PlannedEdit> edits) throws IOException {
        validate(edits);
        List<PlannedEdit> applied = new ArrayList<>();
        try {
            for (PlannedEdit edit : edits) { AtomicFileWriter.write(edit.target(), edit.replacement()); applied.add(edit); }
        } catch (IOException failure) {
            for (int i = applied.size() - 1; i >= 0; i--) {
                PlannedEdit edit = applied.get(i);
                if (edit.original().length == 0) Files.deleteIfExists(edit.target()); else AtomicFileWriter.write(edit.target(), edit.original());
            }
            throw failure;
        }
    }

    private static void validate(List<PlannedEdit> edits) throws IOException {
        HashSet<Path> targets = new HashSet<>();
        for (PlannedEdit edit : edits) {
            Path target = edit.target().toAbsolutePath().normalize();
            if (!targets.add(target)) throw new IOException("duplicate staging target");
            byte[] current = Files.isRegularFile(target) ? Files.readAllBytes(target) : new byte[0];
            if (!Arrays.equals(current, edit.original())) {
                throw new IOException("staging target changed after planning");
            }
        }
    }
}
