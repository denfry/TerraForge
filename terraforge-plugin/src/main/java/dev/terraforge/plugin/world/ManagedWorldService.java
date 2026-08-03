package dev.terraforge.plugin.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Coordination surface for a restart-bound, primary earth creation: {@link #plan}, {@link #stage},
 * {@link #abort} and {@link #status}. Delegates the mechanics of staging and aborting to
 * {@link ManagedWorldStager} and {@link ManagedWorldAbort} respectively, and never itself calls
 * {@code WorldCreator}, deletes a real world, edits {@code spawn}, stops the server, or touches
 * watchdog settings.
 */
public final class ManagedWorldService {
    private static final long GIB = 1024L * 1024L * 1024L;

    private final ManagedWorldManifestStore manifests;
    private final ManagedWorldStager stager;
    private final ManagedWorldAbort aborter;

    /** Plan-only instance; {@link #stage}, {@link #abort} and {@link #status} are unavailable. */
    public ManagedWorldService() { this.manifests = null; this.stager = null; this.aborter = null; }

    /** Full coordinator, backed by the manifest store rooted at {@code pluginRoot}. */
    public ManagedWorldService(Path pluginRoot) {
        this.manifests = new ManagedWorldManifestStore(pluginRoot);
        this.stager = new ManagedWorldStager(pluginRoot);
        this.aborter = new ManagedWorldAbort(manifests);
    }

    public WorldCreationPlan plan(ManagedWorldEnvironment environment, String configuredName, long minimumFreeDiskGb) {
        List<WorldCreationCheck> checks = new ArrayList<>();
        checks.add(new WorldCreationCheck("paper", environment.isOfficialSupportedPaper(), "official supported Paper is required"));
        checks.add(new WorldCreationCheck("world-name", "earth".equals(configuredName), "managed primary world must be named earth"));
        checks.add(new WorldCreationCheck("loaded-world", !environment.isWorldLoaded("earth"), "earth must not already be loaded"));
        Path earth = environment.worldContainer().resolve("earth");
        checks.add(new WorldCreationCheck("existing-world", !Files.exists(earth), "earth directory must not already exist"));
        checks.add(new WorldCreationCheck("prepared-dem", environment.hasPreparedDem(), "prepared DEM tiles are required"));
        try { checks.add(new WorldCreationCheck("disk-space", environment.usableDiskBytes(environment.worldContainer()) >= minimumFreeDiskGb * GIB, "insufficient usable disk space")); }
        catch (IOException exception) { checks.add(new WorldCreationCheck("disk-space", false, "cannot inspect usable disk space")); }
        return new WorldCreationPlan(checks);
    }

    /**
     * Stages {@code edits} (server-property, bukkit.yml and paper-world.yml changes plus the rendered
     * datapack) behind a recoverable transaction and records {@code manifest} as pending-restart.
     * Idempotent only when an identical manifest is already pending: a repeat call with the same
     * manifest is a no-op. A differing manifest, or an existing manifest not in PENDING_RESTART, is refused.
     */
    public void stage(Path serverRoot, Path worldContainer, ManagedWorldManifest manifest, List<PlannedEdit> edits) throws IOException {
        requireCoordinator();
        Optional<ManagedWorldManifest> existing = manifests.load();
        if (existing.isPresent()) {
            if (existing.get().equals(manifest) && existing.get().state() == ManagedWorldState.PENDING_RESTART) {
                return; // already staged with an identical manifest: nothing more to do
            }
            throw new IOException("refusing to stage: a different managed-world manifest is already present");
        }
        stager.stage(serverRoot, worldContainer, manifest, edits);
    }

    /** Deletes a staged-but-not-yet-created earth world only when TerraForge provably owns every entry inside it. */
    public void abort(Path serverRoot, Path worldContainer) throws IOException {
        requireCoordinator();
        aborter.abort(serverRoot, worldContainer);
    }

    /** Current managed-world lifecycle state, or {@link ManagedWorldState#ABSENT} when no manifest exists. */
    public ManagedWorldState status() throws IOException {
        requireCoordinator();
        return manifests.load().map(ManagedWorldManifest::state).orElse(ManagedWorldState.ABSENT);
    }

    private void requireCoordinator() {
        if (manifests == null) throw new IllegalStateException("ManagedWorldService(Path pluginRoot) is required for stage/abort/status");
    }
}
