package dev.terraforge.plugin.world;

import java.nio.file.Path;

/** A fully validated in-memory edit; planning never mutates the server. */
public record PlannedEdit(Path target, byte[] original, byte[] replacement) {
    public PlannedEdit { original = original.clone(); replacement = replacement.clone(); }
}
