package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.terraforge.core.config.VerticalProfile;
import io.papermc.paper.datapack.DatapackRegistrar;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedWorldAdoptTest {
    private static final String DEM = "b".repeat(64);
    private static final VerticalProfile VANILLA = VerticalProfile.regional();

    @TempDir Path pluginRoot;

    private Function<ManagedWorldManifest, LiveWorldSnapshot> live(boolean primary, boolean generator, int minY, int maxY, String dem) {
        // A vanilla-height world on 26.x: no TerraForge datapack is enabled at all.
        return manifest -> new LiveWorldSnapshot("earth", primary, generator, minY, maxY, false,
                VANILLA.fingerprint(), dem, manifest.datapackFingerprint());
    }

    @Test
    void adoptsAnExistingVanillaHeightWorldWithoutADatapack() throws Exception {
        var service = new ManagedWorldService(pluginRoot);
        List<String> failures = new ArrayList<>();

        boolean ready = service.adopt("earth", VANILLA, live(true, true, -64, 320, DEM), failures::add);

        assertThat(failures).isEmpty();
        assertThat(ready).isTrue();
        ManagedWorldManifest manifest = new ManagedWorldManifestStore(pluginRoot).load().orElseThrow();
        assertThat(manifest.state()).isEqualTo(ManagedWorldState.READY);
        assertThat(manifest.withoutDatapack()).isTrue();
        assertThat(manifest.dataFingerprint()).isEqualTo(DEM);
    }

    @Test
    void anAdoptedWorldStaysReadyOnTheNextStartupAndNeedsNoDatapackAtBootstrap() throws Exception {
        var service = new ManagedWorldService(pluginRoot);
        assertThat(service.adopt("earth", VANILLA, live(true, true, -64, 320, DEM), failure -> {})).isTrue();

        var verifier = new ManagedWorldStartupVerifier(new ManagedWorldManifestStore(pluginRoot), new LiveWorldVerifier());
        List<String> failures = new ArrayList<>();
        assertThat(verifier.verifyOnEnable(live(true, true, -64, 320, DEM), failures::add)).isTrue();
        assertThat(failures).isEmpty();

        // plugins/TerraForge -> server root two levels up; there is no earth/datapacks folder at all.
        Path dataDir = pluginRoot.resolve("plugins").resolve("TerraForge");
        Files.createDirectories(dataDir);
        Files.copy(pluginRoot.resolve("managed-world.json"), dataDir.resolve("managed-world.json"));
        DatapackRegistrar registrar = mock(DatapackRegistrar.class);
        new BootstrapDatapackService().discover(dataDir, registrar);
        verifyNoInteractions(registrar);
    }

    @Test
    void refusesWhenAManifestIsAlreadyPresent() throws Exception {
        var service = new ManagedWorldService(pluginRoot);
        assertThat(service.adopt("earth", VANILLA, live(true, true, -64, 320, DEM), failure -> {})).isTrue();
        List<String> failures = new ArrayList<>();

        assertThat(service.adopt("earth", VANILLA, live(true, true, -64, 320, DEM), failures::add)).isFalse();
        assertThat(failures).singleElement().asString().contains("already present");
    }

    @Test
    void refusesAWorldThatIsNotTerraForgeNotPrimaryOrOfAnotherHeight() throws Exception {
        var service = new ManagedWorldService(pluginRoot);
        List<String> failures = new ArrayList<>();

        assertThat(service.adopt("earth", VANILLA, live(false, false, -64, 256, DEM), failures::add)).isFalse();

        assertThat(failures).hasSize(3);
        assertThat(new ManagedWorldManifestStore(pluginRoot).load()).isEmpty();
    }

    @Test
    void waitsForTheDemFingerprintInsteadOfRecordingAnEmptyOne() throws Exception {
        var service = new ManagedWorldService(pluginRoot);
        List<String> failures = new ArrayList<>();

        assertThat(service.adopt("earth", VANILLA, live(true, true, -64, 320, ""), failures::add)).isFalse();

        assertThat(failures).singleElement().asString().contains("DEM fingerprint");
        assertThat(new ManagedWorldManifestStore(pluginRoot).load()).isEmpty();
    }

    @Test
    void refusesATallerProfileThatWouldNeedAHeightDatapack() throws Exception {
        var service = new ManagedWorldService(pluginRoot);
        List<String> failures = new ArrayList<>();

        assertThat(service.adopt("earth", VerticalProfile.planet(), live(true, true, -512, 512, DEM), failures::add)).isFalse();

        assertThat(failures).singleElement().asString().contains("vanilla height");
    }
}
