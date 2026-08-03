package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.terraforge.core.config.VerticalProfile;
import dev.terraforge.core.world.TerraForgeDatapack;
import io.papermc.paper.datapack.DatapackRegistrar;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootstrapDatapackServiceTest {
    private static final String HASH = "a".repeat(64);
    @TempDir Path directory;

    @Test
    void noManifestLeavesInstallationInert() throws Exception {
        DatapackRegistrar registrar = mock(DatapackRegistrar.class);

        new BootstrapDatapackService().discover(directory, registrar);

        verify(registrar, never()).discoverPack(any(Path.class), anyString(), any());
    }

    @Test
    void rejectsTamperedPackBeforeDiscovery() throws Exception {
        VerticalProfile profile = new VerticalProfile(0, -512, 512, 20.0);
        var rendered = TerraForgeDatapack.render(profile);
        Path pack = directory.resolve("datapack/terraforge-world-height");
        for (var entry : rendered.files().entrySet()) {
            Path target = pack.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        Files.writeString(pack.resolve("pack.mcmeta"), "tampered");
        new ManagedWorldManifestStore(directory).save(new ManagedWorldManifest(1,
                ManagedWorldState.PENDING_RESTART, "earth", -512, 512, HASH, HASH,
                rendered.fingerprint(), List.of("datapack/terraforge-world-height/pack.mcmeta"),
                "pending restart"));

        assertThatThrownBy(() -> new BootstrapDatapackService().discover(directory,
                mock(DatapackRegistrar.class))).isInstanceOf(java.io.IOException.class);
    }
}
