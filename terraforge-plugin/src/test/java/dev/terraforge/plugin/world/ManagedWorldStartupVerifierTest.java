package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedWorldStartupVerifierTest {
    private static final String HASH = "a".repeat(64);

    @TempDir Path pluginRoot;

    @Test
    void noManifestSkipsVerificationAndReportsNotReady() throws Exception {
        var verifier = new ManagedWorldStartupVerifier(new ManagedWorldManifestStore(pluginRoot), new LiveWorldVerifier());
        List<String> failures = new ArrayList<>();

        boolean ready = verifier.verifyOnEnable(manifest -> matchingSnapshot(manifest), failures::add);

        assertThat(ready).isFalse();
        assertThat(failures).isEmpty();
    }

    @Test
    void invalidManifestIsLeftUntouchedAndSkipped() throws Exception {
        var store = new ManagedWorldManifestStore(pluginRoot);
        store.save(pending());
        store.save(withState(pending(), ManagedWorldState.CREATING));
        store.save(withState(pending(), ManagedWorldState.INVALID));
        var verifier = new ManagedWorldStartupVerifier(store, new LiveWorldVerifier());
        List<String> failures = new ArrayList<>();

        boolean ready = verifier.verifyOnEnable(manifest -> matchingSnapshot(manifest), failures::add);

        assertThat(ready).isFalse();
        assertThat(failures).isEmpty();
        assertThat(store.load().orElseThrow().state()).isEqualTo(ManagedWorldState.INVALID);
    }

    @Test
    void pendingRestartIsPromotedToCreatingThenReadyOnAMatchingLiveWorld() throws Exception {
        var store = new ManagedWorldManifestStore(pluginRoot);
        store.save(pending());
        var verifier = new ManagedWorldStartupVerifier(store, new LiveWorldVerifier());
        List<String> failures = new ArrayList<>();

        boolean ready = verifier.verifyOnEnable(manifest -> matchingSnapshot(manifest), failures::add);

        assertThat(ready).isTrue();
        assertThat(failures).isEmpty();
        assertThat(store.load().orElseThrow().state()).isEqualTo(ManagedWorldState.READY);
    }

    @Test
    void mismatchWritesInvalidAndLogsEveryFailure() throws Exception {
        var store = new ManagedWorldManifestStore(pluginRoot);
        store.save(pending());
        var verifier = new ManagedWorldStartupVerifier(store, new LiveWorldVerifier());
        List<String> failures = new ArrayList<>();

        boolean ready = verifier.verifyOnEnable(
                manifest -> new LiveWorldSnapshot("spawn", false, false, -64, 320, false,
                        "b".repeat(64), "b".repeat(64), "b".repeat(64)),
                failures::add);

        assertThat(ready).isFalse();
        assertThat(failures).hasSize(8);
        assertThat(store.load().orElseThrow().state()).isEqualTo(ManagedWorldState.INVALID);
    }

    @Test
    void anAlreadyReadyManifestIsReVerifiedWithoutRevisitingCreating() throws Exception {
        var store = new ManagedWorldManifestStore(pluginRoot);
        store.save(pending());
        store.save(withState(pending(), ManagedWorldState.CREATING));
        store.save(withState(pending(), ManagedWorldState.READY));
        var verifier = new ManagedWorldStartupVerifier(store, new LiveWorldVerifier());
        List<String> failures = new ArrayList<>();

        boolean ready = verifier.verifyOnEnable(manifest -> matchingSnapshot(manifest), failures::add);

        assertThat(ready).isTrue();
        assertThat(failures).isEmpty();
        assertThat(store.load().orElseThrow().state()).isEqualTo(ManagedWorldState.READY);
    }

    private static LiveWorldSnapshot matchingSnapshot(ManagedWorldManifest manifest) {
        return new LiveWorldSnapshot(manifest.worldName(), true, true, manifest.minY(), manifest.maxY(), true,
                manifest.configFingerprint(), manifest.dataFingerprint(), manifest.datapackFingerprint());
    }

    private static ManagedWorldManifest pending() {
        return new ManagedWorldManifest(1, ManagedWorldState.PENDING_RESTART, "earth", -512, 512, HASH, HASH, HASH,
                List.of("earth/datapacks/terraforge-earth-height/pack.mcmeta"), "managed earth world staged by TerraForge");
    }

    private static ManagedWorldManifest withState(ManagedWorldManifest manifest, ManagedWorldState state) {
        return new ManagedWorldManifest(manifest.schemaVersion(), state, manifest.worldName(), manifest.minY(),
                manifest.maxY(), manifest.configFingerprint(), manifest.dataFingerprint(),
                manifest.datapackFingerprint(), manifest.ownedStagingFiles(), manifest.reason());
    }
}
