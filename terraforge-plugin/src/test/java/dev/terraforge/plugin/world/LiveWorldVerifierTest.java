package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LiveWorldVerifierTest {
    private static final String HASH = "a".repeat(64);
    @Test void acceptsOnlyAnExactlyMatchingPrimaryEarth() {
        var manifest = new ManagedWorldManifest(1, ManagedWorldState.CREATING, "earth", -512, 512, HASH, HASH, HASH, List.of(), null);
        var world = new LiveWorldSnapshot("earth", true, true, -512, 512, true, HASH, HASH, HASH);
        assertThat(new LiveWorldVerifier().verify(manifest, world)).isEmpty();
    }
    @Test void reportsAllUnsafeMismatches() {
        var manifest = new ManagedWorldManifest(1, ManagedWorldState.CREATING, "earth", -512, 512, HASH, HASH, HASH, List.of(), null);
        var world = new LiveWorldSnapshot("spawn", false, false, -64, 320, false, "b".repeat(64), "b".repeat(64), "b".repeat(64));
        assertThat(new LiveWorldVerifier().verify(manifest, world)).hasSize(8);
    }
}
