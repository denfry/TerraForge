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
    private static final String PACK_RELATIVE_ROOT = "earth/datapacks/terraforge-earth-height";

    @TempDir Path serverRoot;

    @Test
    void noManifestLeavesInstallationInert() throws Exception {
        DatapackRegistrar registrar = mock(DatapackRegistrar.class);

        new BootstrapDatapackService().discover(pluginDataDirectory(), registrar);

        verify(registrar, never()).discoverPack(any(Path.class), anyString(), any());
    }

    @Test
    void discoversAMatchingPendingRestartPack() throws Exception {
        VerticalProfile profile = new VerticalProfile(0, -512, 512, 20.0);
        var rendered = writePack(profile);
        new ManagedWorldManifestStore(pluginDataDirectory()).save(new ManagedWorldManifest(1,
                ManagedWorldState.PENDING_RESTART, "earth", -512, 512, HASH, HASH,
                rendered.fingerprint(), ownedFiles(rendered), "pending restart"));
        DatapackRegistrar registrar = mock(DatapackRegistrar.class);

        new BootstrapDatapackService().discover(pluginDataDirectory(), registrar);

        verify(registrar).discoverPack(any(Path.class), anyString(), any());
    }

    @Test
    void rejectsTamperedPackBeforeDiscovery() throws Exception {
        VerticalProfile profile = new VerticalProfile(0, -512, 512, 20.0);
        var rendered = writePack(profile);
        Files.writeString(serverRoot.resolve(PACK_RELATIVE_ROOT).resolve("pack.mcmeta"), "tampered");
        new ManagedWorldManifestStore(pluginDataDirectory()).save(new ManagedWorldManifest(1,
                ManagedWorldState.PENDING_RESTART, "earth", -512, 512, HASH, HASH,
                rendered.fingerprint(), ownedFiles(rendered), "pending restart"));

        assertThatThrownBy(() -> new BootstrapDatapackService().discover(pluginDataDirectory(),
                mock(DatapackRegistrar.class))).isInstanceOf(java.io.IOException.class);
    }

    private TerraForgeDatapack.RenderedPack writePack(VerticalProfile profile) throws Exception {
        var rendered = TerraForgeDatapack.render(profile);
        Path pack = serverRoot.resolve(PACK_RELATIVE_ROOT);
        for (var entry : rendered.files().entrySet()) {
            Path target = pack.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        return rendered;
    }

    private List<String> ownedFiles(TerraForgeDatapack.RenderedPack rendered) {
        return rendered.files().keySet().stream().map(name -> PACK_RELATIVE_ROOT + "/" + name).toList();
    }

    private Path pluginDataDirectory() { return serverRoot.resolve("plugins/TerraForge"); }
}
