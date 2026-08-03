package dev.terraforge.plugin.world;

import dev.terraforge.plugin.io.AtomicFileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Arrays;

/** Applies only preplanned edits and restores exact bytes if any later write fails. */
public final class WorldStagingTransaction {
    /** Creates durable original-byte backups before applying a set of edits. */
    public void commit(List<PlannedEdit> edits, Path backupRoot) throws IOException {
        validate(edits);
        for (int i = 0; i < edits.size(); i++) {
            PlannedEdit edit = edits.get(i);
            AtomicFileWriter.write(backupRoot.resolve(String.format("%03d.bak", i)), edit.original());
        }
        commit(edits);
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
