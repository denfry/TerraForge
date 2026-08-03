package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.terraforge.core.config.VerticalProfile;
import dev.terraforge.core.world.TerraForgeDatapack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedWorldServiceTest {
    private static final String OTHER_HASH = "b".repeat(64);
    private static final PaperWorldSettingsEditor.ChunkSettings CHUNK_SETTINGS =
            new PaperWorldSettingsEditor.ChunkSettings(6000, 24, "10s");

    @TempDir Path serverRoot;

    @Test void planPerformsZeroFilesystemWrites() throws Exception {
        Files.createDirectories(serverRoot.resolve("worlds"));
        Path dem = demDirectory();
        List<String> before = listEntries();

        WorldCreationPlan plan = new ManagedWorldService().plan(executableEnvironment("worlds"), "earth", 10,
                VerticalProfile.regional(), dem, CHUNK_SETTINGS);

        assertThat(plan.executable()).isTrue();
        assertThat(listEntries()).isEqualTo(before);
    }

    @Test void planComputesFingerprintsFromTheVerticalProfileAndDemDirectoryRatherThanAcceptingThemAsInput() throws Exception {
        Files.createDirectories(serverRoot.resolve("worlds"));
        Path dem = demDirectory();

        WorldCreationPlan plan = new ManagedWorldService().plan(executableEnvironment("worlds"), "earth", 10,
                VerticalProfile.regional(), dem, CHUNK_SETTINGS);

        assertThat(plan.configFingerprint()).isEqualTo(VerticalProfile.regional().fingerprint());
        assertThat(plan.dataFingerprint()).isEqualTo(DemDataFingerprint.of(dem));
    }

    @Test void stageAssemblesEditsFromTheProfileRendererAndEditors() throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        ManagedWorldService service = new ManagedWorldService(pluginRoot());
        WorldCreationPlan plan = executablePlan();

        service.stage(plan);

        assertThat(Files.readString(serverRoot.resolve("server.properties"))).contains("level-name=earth");
        assertThat(Files.readString(serverRoot.resolve("bukkit.yml"))).contains("generator: \"TerraForge\"");
        assertThat(Files.readString(worldContainer().resolve("earth/paper-world.yml")))
                .contains("auto-save-interval: 6000", "max-auto-save-chunks-per-tick: 24", "delay-chunk-unloads-by: \"10s\"");
        TerraForgeDatapack.RenderedPack pack = TerraForgeDatapack.render(VerticalProfile.regional());
        for (String fileName : pack.files().keySet()) {
            assertThat(Files.exists(worldContainer().resolve("earth/datapacks/terraforge-earth-height").resolve(fileName))).isTrue();
        }
        ManagedWorldManifest manifest = manifests.load().orElseThrow();
        assertThat(manifest.state()).isEqualTo(ManagedWorldState.PENDING_RESTART);
        assertThat(manifest.datapackFingerprint()).isEqualTo(pack.fingerprint());
        assertThat(manifest.minY()).isEqualTo(VerticalProfile.regional().minY());
        assertThat(manifest.maxY()).isEqualTo(VerticalProfile.regional().maxY());
        assertThat(manifest.configFingerprint()).isEqualTo(VerticalProfile.regional().fingerprint());
        assertThat(manifest.dataFingerprint()).isEqualTo(DemDataFingerprint.of(demDirectory()));
    }

    @Test void stageIsIdempotentForAnIdenticalPendingManifest() throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        ManagedWorldService service = new ManagedWorldService(pluginRoot());
        WorldCreationPlan plan = executablePlan();

        service.stage(plan);
        ManagedWorldManifest first = manifests.load().orElseThrow();

        service.stage(plan);

        assertThat(manifests.load()).contains(first);
        assertThat(Files.readString(worldContainer().resolve("earth/paper-world.yml")))
                .contains("auto-save-interval: 6000");
    }

    @Test void stageRefusesAPlanThatAssemblesADifferentManifest() throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        ManagedWorldService service = new ManagedWorldService(pluginRoot());
        WorldCreationPlan first = executablePlan();
        service.stage(first);
        ManagedWorldManifest staged = manifests.load().orElseThrow();

        WorldCreationPlan different = new WorldCreationPlan(first.checks(), first.serverRoot(), first.worldContainer(),
                first.worldName(), first.verticalProfile(), OTHER_HASH, first.dataFingerprint(), first.chunkSettings());

        assertThatThrownBy(() -> service.stage(different)).isInstanceOf(IOException.class);
        assertThat(manifests.load()).contains(staged);
    }

    @Test void stageRefusesWhenTheExistingManifestIsNotPendingRestart() throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        ManagedWorldService service = new ManagedWorldService(pluginRoot());
        WorldCreationPlan plan = executablePlan();
        service.stage(plan);
        ManagedWorldManifest pending = manifests.load().orElseThrow();
        manifests.save(new ManagedWorldManifest(pending.schemaVersion(), ManagedWorldState.CREATING, pending.worldName(),
                pending.minY(), pending.maxY(), pending.configFingerprint(), pending.dataFingerprint(),
                pending.datapackFingerprint(), pending.ownedStagingFiles(), pending.reason()));

        assertThatThrownBy(() -> service.stage(plan)).isInstanceOf(IOException.class);
    }

    @Test void stageRefusesANonExecutablePlan() throws Exception {
        ManagedWorldService service = new ManagedWorldService(pluginRoot());
        WorldCreationPlan plan = new ManagedWorldService().plan(executableEnvironment("world"), "spawn", 10,
                VerticalProfile.regional(), demDirectory(), CHUNK_SETTINGS);

        assertThatThrownBy(() -> service.stage(plan)).isInstanceOf(IOException.class);
    }

    private WorldCreationPlan executablePlan() {
        return new ManagedWorldService().plan(executableEnvironment("world"), "earth", 10, VerticalProfile.regional(),
                demDirectory(), CHUNK_SETTINGS);
    }

    private Path demDirectory() {
        Path dem = serverRoot.resolve("dem-fixture");
        try {
            Files.createDirectories(dem);
            Files.writeString(dem.resolve("tile-0.dem"), "elevation-fixture-data");
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
        return dem;
    }

    private ManagedWorldEnvironment executableEnvironment(String worldContainerName) {
        return new ManagedWorldEnvironment() {
            public boolean isOfficialSupportedPaper() { return true; }
            public boolean isWorldLoaded(String name) { return false; }
            public boolean hasPreparedDem() { return true; }
            public Path serverRoot() { return serverRoot; }
            public Path worldContainer() { return serverRoot.resolve(worldContainerName); }
            public long usableDiskBytes(Path path) { return Long.MAX_VALUE; }
        };
    }

    private List<String> listEntries() throws IOException {
        try (var walk = Files.walk(serverRoot)) {
            return walk.map(serverRoot::relativize).map(Path::toString).sorted().collect(Collectors.toList());
        }
    }

    private Path pluginRoot() { return serverRoot.resolve("plugins/TerraForge"); }
    private Path worldContainer() { return serverRoot.resolve("world"); }
}
