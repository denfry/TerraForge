package dev.terraforge.plugin.world;

import dev.terraforge.core.config.VerticalProfile;
import dev.terraforge.core.world.TerraForgeDatapack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Coordination surface for a restart-bound, primary earth creation: {@link #plan}, {@link #stage},
 * {@link #abort} and {@link #status}. {@link #stage} assembles the staged edits itself — rendering the
 * height datapack via {@link TerraForgeDatapack} and building the server-properties, bukkit.yml and
 * paper-world.yml edits via {@link ServerPropertiesEditor}, {@link BukkitWorldsEditor} and
 * {@link PaperWorldSettingsEditor} — then delegates the write/staging mechanics to
 * {@link ManagedWorldStager}. {@link #abort} delegates to {@link ManagedWorldAbort}. This class never
 * itself calls {@code WorldCreator}, deletes a real world, edits {@code spawn}, stops the server, or
 * touches watchdog settings.
 */
public final class ManagedWorldService {
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final String DATAPACK_DIRECTORY_NAME = "terraforge-earth-height";

    private final ManagedWorldManifestStore manifests;
    private final ManagedWorldStager stager;
    private final ManagedWorldAbort aborter;
    private final ManagedWorldStartupVerifier verifier;

    /** Plan-only instance; {@link #stage}, {@link #abort}, {@link #status} and {@link #verify} are unavailable. */
    public ManagedWorldService() { this.manifests = null; this.stager = null; this.aborter = null; this.verifier = null; }

    /** Full coordinator, backed by the manifest store rooted at {@code pluginRoot}. */
    public ManagedWorldService(Path pluginRoot) {
        this.manifests = new ManagedWorldManifestStore(pluginRoot);
        this.stager = new ManagedWorldStager(pluginRoot);
        this.aborter = new ManagedWorldAbort(manifests);
        this.verifier = new ManagedWorldStartupVerifier(manifests, new LiveWorldVerifier());
    }

    /**
     * Checks every prerequisite and, when they all pass, packages what {@link #stage} needs to build the
     * staged edits: the vertical profile driving world height and the height datapack, the config and DEM
     * data fingerprints recorded into the manifest, and the paper-world.yml chunk settings to apply.
     * <p>
     * The config and data fingerprints are always computed here, via {@link VerticalProfile#fingerprint()}
     * and {@link DemDataFingerprint#of(Path)}, rather than accepted as caller-supplied strings: this is the
     * single write-time call site, so it can never drift from the algorithm {@code TerraForgePlugin} uses
     * to recompute both fingerprints live for verification.
     */
    public WorldCreationPlan plan(ManagedWorldEnvironment environment, String configuredName, long minimumFreeDiskGb,
                                   VerticalProfile verticalProfile, Path demDirectory,
                                   PaperWorldSettingsEditor.ChunkSettings chunkSettings) {
        List<WorldCreationCheck> checks = new ArrayList<>();
        checks.add(new WorldCreationCheck("paper", environment.isOfficialSupportedPaper(), "official supported Paper is required"));
        checks.add(new WorldCreationCheck("world-name", "earth".equals(configuredName), "managed primary world must be named earth"));
        checks.add(new WorldCreationCheck("loaded-world", !environment.isWorldLoaded("earth"), "earth must not already be loaded"));
        Path earth = environment.worldContainer().resolve("earth");
        checks.add(new WorldCreationCheck("existing-world", !Files.exists(earth), "earth directory must not already exist"));
        checks.add(new WorldCreationCheck("prepared-dem", environment.hasPreparedDem(), "prepared DEM tiles are required"));
        try { checks.add(new WorldCreationCheck("disk-space", environment.usableDiskBytes(environment.worldContainer()) >= minimumFreeDiskGb * GIB, "insufficient usable disk space")); }
        catch (IOException exception) { checks.add(new WorldCreationCheck("disk-space", false, "cannot inspect usable disk space")); }
        String configFingerprint = verticalProfile.fingerprint();
        String dataFingerprint = DemDataFingerprint.of(demDirectory);
        return new WorldCreationPlan(checks, environment.serverRoot(), environment.worldContainer(), "earth",
                verticalProfile, configFingerprint, dataFingerprint, chunkSettings);
    }

    /**
     * Renders the height datapack from {@code plan.verticalProfile()}, builds the server-properties,
     * bukkit.yml and paper-world.yml edits via the existing editors, and stages the resulting manifest
     * (PENDING_RESTART) behind a recoverable transaction via {@link ManagedWorldStager}.
     * <p>
     * Idempotent only when an identical manifest is already pending: a repeat call that assembles the
     * exact same manifest is a no-op. A differing manifest, or an existing manifest not in
     * PENDING_RESTART, is refused. {@code plan} itself must be {@link WorldCreationPlan#executable()}.
     */
    public void stage(WorldCreationPlan plan) throws IOException {
        requireCoordinator();
        if (!plan.executable()) throw new IOException("refusing to stage: plan is not executable");

        Path earth = plan.worldContainer().resolve(plan.worldName());
        Path paperWorldYaml = earth.resolve("paper-world.yml");
        List<PlannedEdit> edits = new ArrayList<>();
        edits.add(new ServerPropertiesEditor().plan(plan.serverRoot().resolve("server.properties"), plan.worldName()));
        edits.add(new BukkitWorldsEditor().plan(plan.serverRoot().resolve("bukkit.yml")));
        edits.add(new PaperWorldSettingsEditor().plan(paperWorldYaml, plan.chunkSettings()));

        TerraForgeDatapack.RenderedPack pack = TerraForgeDatapack.render(plan.verticalProfile());
        Path datapackRoot = earth.resolve("datapacks").resolve(DATAPACK_DIRECTORY_NAME);
        List<String> ownedStagingFiles = new ArrayList<>();
        ownedStagingFiles.add(relativize(plan.serverRoot(), paperWorldYaml));
        for (var entry : pack.files().entrySet()) {
            Path target = datapackRoot.resolve(entry.getKey());
            edits.add(new PlannedEdit(target, new byte[0], entry.getValue()));
            ownedStagingFiles.add(relativize(plan.serverRoot(), target));
        }

        ManagedWorldManifest manifest = new ManagedWorldManifest(ManagedWorldManifest.SCHEMA_VERSION,
                ManagedWorldState.PENDING_RESTART, plan.worldName(), plan.verticalProfile().minY(),
                plan.verticalProfile().maxY(), plan.configFingerprint(), plan.dataFingerprint(), pack.fingerprint(),
                ownedStagingFiles, "managed earth world staged by TerraForge");

        Optional<ManagedWorldManifest> existing = manifests.load();
        if (existing.isPresent()) {
            // manifest.state() above is always PENDING_RESTART, so equals() already implies the existing
            // manifest is PENDING_RESTART too; a separate state check here can never be false when equals()
            // is true, so it would be dead code kept only for a false sense of extra safety.
            if (existing.get().equals(manifest)) return; // already staged with an identical manifest
            throw new IOException("refusing to stage: a different managed-world manifest is already present");
        }
        stager.stage(plan.serverRoot(), plan.worldContainer(), manifest, edits);
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

    /**
     * Re-runs the same verification {@link ManagedWorldStartupVerifier} performs on plugin enable, on
     * operator demand -- e.g. after suspecting drift without wanting to restart the server. Deliberately
     * reuses that class rather than duplicating its state transitions and comparison logic, so an
     * on-demand verify and the startup verify can never quietly diverge.
     *
     * @param snapshotFactory builds the live snapshot to check the current manifest against
     * @param onFailure       receives every individual check failure, for reporting to the caller
     * @return true once the managed world is verified and ready for managed operations
     */
    public boolean verify(Function<ManagedWorldManifest, LiveWorldSnapshot> snapshotFactory, Consumer<String> onFailure) throws IOException {
        requireCoordinator();
        return verifier.verifyOnEnable(snapshotFactory, onFailure);
    }

    private static String relativize(Path root, Path target) {
        return root.relativize(target).toString().replace('\\', '/');
    }

    private void requireCoordinator() {
        if (manifests == null) throw new IllegalStateException("ManagedWorldService(Path pluginRoot) is required for stage/abort/status");
    }
}
