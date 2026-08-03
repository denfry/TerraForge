package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedWorldManifestStoreTest {
    private static final String HASH = "a".repeat(64);
    @TempDir java.nio.file.Path directory;

    @Test
    void rejectsSkippedLifecycleTransitions() throws Exception {
        var store = new ManagedWorldManifestStore(directory);
        store.save(manifest(ManagedWorldState.PENDING_RESTART));

        assertThatThrownBy(() -> store.save(manifest(ManagedWorldState.READY)))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void rejectsUnsafeOwnedPaths() {
        var store = new ManagedWorldManifestStore(directory);
        var unsafe = new ManagedWorldManifest(1, ManagedWorldState.PENDING_RESTART, "earth", -64, 320,
                HASH, HASH, HASH, List.of("../server.properties"), "pending restart");

        assertThatThrownBy(() -> store.save(unsafe)).isInstanceOf(java.io.IOException.class);
    }

    private static ManagedWorldManifest manifest(ManagedWorldState state) {
        return new ManagedWorldManifest(1, state, "earth", -64, 320, HASH, HASH, HASH,
                List.of("datapack/terraforge-world-height/pack.mcmeta"), state.name());
    }
}
