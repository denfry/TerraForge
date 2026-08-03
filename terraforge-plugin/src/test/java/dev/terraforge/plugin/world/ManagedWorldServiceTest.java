package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedWorldServiceTest {
    private static final String HASH = "a".repeat(64);

    @TempDir Path serverRoot;

    @Test void planPerformsZeroFilesystemWrites() throws Exception {
        Files.createDirectories(serverRoot.resolve("worlds"));
        ManagedWorldEnvironment environment = new ManagedWorldEnvironment() {
            public boolean isOfficialSupportedPaper() { return true; }
            public boolean isWorldLoaded(String name) { return false; }
            public boolean hasPreparedDem() { return true; }
            public Path serverRoot() { return serverRoot; }
            public Path worldContainer() { return serverRoot.resolve("worlds"); }
            public long usableDiskBytes(Path path) { return Long.MAX_VALUE; }
        };
        List<String> before = listEntries();

        WorldCreationPlan plan = new ManagedWorldService().plan(environment, "earth", 10);

        assertThat(plan.executable()).isTrue();
        assertThat(listEntries()).isEqualTo(before);
    }

    @Test void stageIsIdempotentForAnIdenticalPendingManifest() throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        ManagedWorldService service = new ManagedWorldService(pluginRoot());
        ManagedWorldManifest manifest = manifest("pending restart");
        List<PlannedEdit> edits = List.of(edit(worldContainer().resolve("earth/paper-world.yml"),
                new byte[0], "chunks:\n  auto-save-interval: 6000\n".getBytes()));

        service.stage(serverRoot, worldContainer(), manifest, edits);
        assertThat(manifests.load()).contains(manifest);

        service.stage(serverRoot, worldContainer(), manifest, edits);

        assertThat(manifests.load()).contains(manifest);
        assertThat(Files.readString(worldContainer().resolve("earth/paper-world.yml")))
                .isEqualTo("chunks:\n  auto-save-interval: 6000\n");
    }

    @Test void stageRefusesADifferentPendingManifest() throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        ManagedWorldService service = new ManagedWorldService(pluginRoot());
        ManagedWorldManifest first = manifest("pending restart");
        List<PlannedEdit> edits = List.of(edit(worldContainer().resolve("earth/paper-world.yml"),
                new byte[0], "chunks:\n  auto-save-interval: 6000\n".getBytes()));
        service.stage(serverRoot, worldContainer(), first, edits);

        ManagedWorldManifest different = manifest("a different reason for staging");

        assertThatThrownBy(() -> service.stage(serverRoot, worldContainer(), different, edits))
                .isInstanceOf(IOException.class);
        assertThat(manifests.load()).contains(first);
    }

    @Test void stageRefusesWhenTheExistingManifestIsNotPendingRestart() throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        ManagedWorldService service = new ManagedWorldService(pluginRoot());
        ManagedWorldManifest pending = manifest("pending restart");
        manifests.save(pending);
        manifests.save(new ManagedWorldManifest(pending.schemaVersion(), ManagedWorldState.CREATING, pending.worldName(),
                pending.minY(), pending.maxY(), pending.configFingerprint(), pending.dataFingerprint(),
                pending.datapackFingerprint(), pending.ownedStagingFiles(), pending.reason()));
        List<PlannedEdit> edits = List.of(edit(worldContainer().resolve("earth/paper-world.yml"),
                new byte[0], "chunks:\n  auto-save-interval: 6000\n".getBytes()));

        assertThatThrownBy(() -> service.stage(serverRoot, worldContainer(), pending, edits))
                .isInstanceOf(IOException.class);
    }

    private List<String> listEntries() throws IOException {
        try (var walk = Files.walk(serverRoot)) {
            return walk.map(serverRoot::relativize).map(Path::toString).sorted().collect(Collectors.toList());
        }
    }

    private Path pluginRoot() { return serverRoot.resolve("plugins/TerraForge"); }
    private Path worldContainer() { return serverRoot.resolve("world"); }

    private static PlannedEdit edit(Path target, byte[] original, byte[] replacement) {
        return new PlannedEdit(target, original, replacement);
    }

    private static ManagedWorldManifest manifest(String reason) {
        return new ManagedWorldManifest(1, ManagedWorldState.PENDING_RESTART, "earth", -64, 320,
                HASH, HASH, HASH, List.of("world/earth/paper-world.yml"), reason);
    }
}
