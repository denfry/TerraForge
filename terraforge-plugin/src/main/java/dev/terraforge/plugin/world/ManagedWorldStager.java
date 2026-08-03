package dev.terraforge.plugin.world;

import dev.terraforge.plugin.io.SafePathResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Coordinates a recoverable primary-earth staging transaction; it never creates or deletes a world. */
public final class ManagedWorldStager {
    private final ManagedWorldManifestStore manifests;
    private final WorldStagingTransaction transaction = new WorldStagingTransaction();

    public ManagedWorldStager(Path pluginRoot) { this.manifests = new ManagedWorldManifestStore(pluginRoot); }

    public void stage(Path serverRoot, Path worldContainer, ManagedWorldManifest manifest,
                      List<PlannedEdit> edits) throws IOException {
        if (manifest.state() != ManagedWorldState.PENDING_RESTART) throw new IOException("manifest must be pending restart");
        if (Files.exists(worldContainer.resolve("earth"))) throw new IOException("refusing to stage over an existing earth directory");
        for (PlannedEdit edit : edits) verifyTarget(serverRoot, edit.target());
        Path backupRoot = SafePathResolver.resolve(serverRoot, Path.of("plugins", "TerraForge", "managed-world-backups"));
        transaction.commit(edits, backupRoot);
        manifests.save(manifest); // Commit marker: written only after every server file is safely staged.
    }

    private static void verifyTarget(Path serverRoot, Path target) throws IOException {
        Path root = serverRoot.toRealPath();
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IOException("staging target escapes server root");
    }
}
