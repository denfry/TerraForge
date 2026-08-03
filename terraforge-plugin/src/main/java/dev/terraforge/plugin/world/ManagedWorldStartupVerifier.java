package dev.terraforge.plugin.world;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs the live verification of a staged managed Earth world on plugin enable: promotes a
 * {@code PENDING_RESTART} manifest to {@code CREATING} before inspecting the running server, then to
 * {@code READY} only once every {@link LiveWorldVerifier} check passes, or to {@code INVALID}
 * (logging every failure) the moment one does not. A manifest that is {@code ABSENT} or already
 * {@code INVALID} is left untouched -- there is nothing staged left to verify, and {@code INVALID} is
 * terminal.
 *
 * <p>Kept free of any Bukkit dependency so it is testable without a running server: the caller
 * supplies a {@link LiveWorldSnapshot} factory instead of a live {@code World}/{@code Server}.
 */
public final class ManagedWorldStartupVerifier {
    private final ManagedWorldManifestStore manifests;
    private final LiveWorldVerifier verifier;

    public ManagedWorldStartupVerifier(ManagedWorldManifestStore manifests, LiveWorldVerifier verifier) {
        this.manifests = manifests;
        this.verifier = verifier;
    }

    /**
     * @param snapshotFactory builds the live snapshot to check against; only invoked once a manifest in
     *                        an eligible state ({@code PENDING_RESTART}, {@code CREATING} or
     *                        {@code READY}) is on disk, so a server with no managed world pays nothing
     * @param onFailure       receives every individual check failure, for logging
     * @return true once the managed world is verified and ready for managed operations
     */
    public boolean verifyOnEnable(Function<ManagedWorldManifest, LiveWorldSnapshot> snapshotFactory,
                                   Consumer<String> onFailure) throws IOException {
        Optional<ManagedWorldManifest> loaded = manifests.load();
        if (loaded.isEmpty()) {
            return false;
        }
        ManagedWorldManifest manifest = loaded.get();
        if (!isEligible(manifest.state())) {
            return false;
        }
        if (manifest.state() == ManagedWorldState.PENDING_RESTART) {
            manifest = withState(manifest, ManagedWorldState.CREATING);
            manifests.save(manifest);
        }

        List<String> failures = verifier.verify(manifest, snapshotFactory.apply(manifest));
        if (failures.isEmpty()) {
            if (manifest.state() != ManagedWorldState.READY) {
                manifests.save(withState(manifest, ManagedWorldState.READY));
            }
            return true;
        }

        failures.forEach(onFailure);
        manifests.save(withState(manifest, ManagedWorldState.INVALID));
        return false;
    }

    private static boolean isEligible(ManagedWorldState state) {
        return state == ManagedWorldState.PENDING_RESTART || state == ManagedWorldState.CREATING
                || state == ManagedWorldState.READY;
    }

    private static ManagedWorldManifest withState(ManagedWorldManifest manifest, ManagedWorldState state) {
        return new ManagedWorldManifest(manifest.schemaVersion(), state, manifest.worldName(), manifest.minY(),
                manifest.maxY(), manifest.configFingerprint(), manifest.dataFingerprint(),
                manifest.datapackFingerprint(), manifest.ownedStagingFiles(), manifest.reason());
    }
}
