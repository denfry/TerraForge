package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedWorldAbortTest {
    private static final String HASH = "a".repeat(64);
    @TempDir Path serverRoot;

    @Test void deletesStagedEarthDirectoryAndManifestWhenOwnershipIsExact() throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        Path earth = earthDir();
        Files.createDirectories(earth);
        Files.writeString(earth.resolve(ManagedWorldAbort.MARKER_FILE_NAME), "staged\n");
        Files.writeString(earth.resolve("paper-world.yml"), "chunks:\n  auto-save-interval: 6000\n");
        manifests.save(manifest(List.of("world/earth/paper-world.yml")));

        new ManagedWorldAbort(manifests).abort(serverRoot, serverRoot.resolve("world"));

        assertThat(Files.exists(earth)).isFalse();
        assertThat(manifests.load()).isEmpty();
    }

    @Test void refusesWhenManifestIsNotPendingRestart() throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        Files.createDirectories(earthDir());
        Files.writeString(earthDir().resolve(ManagedWorldAbort.MARKER_FILE_NAME), "staged\n");

        assertThatThrownBy(() -> new ManagedWorldAbort(manifests).abort(serverRoot, serverRoot.resolve("world")))
                .isInstanceOf(IOException.class);
    }

    @Test void refusesWhenLevelDatIsPresent() throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        Path earth = earthDir();
        Files.createDirectories(earth);
        Files.writeString(earth.resolve(ManagedWorldAbort.MARKER_FILE_NAME), "staged\n");
        Files.write(earth.resolve("level.dat"), new byte[] {1, 2, 3});
        manifests.save(manifest(List.of()));

        assertThatThrownBy(() -> new ManagedWorldAbort(manifests).abort(serverRoot, serverRoot.resolve("world")))
                .isInstanceOf(IOException.class);
        assertThat(Files.exists(earth.resolve("level.dat"))).isTrue();
    }

    @Test void refusesWhenUidDatIsPresent() throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        Path earth = earthDir();
        Files.createDirectories(earth);
        Files.writeString(earth.resolve(ManagedWorldAbort.MARKER_FILE_NAME), "staged\n");
        Files.write(earth.resolve("uid.dat"), new byte[] {1});
        manifests.save(manifest(List.of()));

        assertThatThrownBy(() -> new ManagedWorldAbort(manifests).abort(serverRoot, serverRoot.resolve("world")))
                .isInstanceOf(IOException.class);
    }

    @Test void refusesWhenRegionDirectoryIsPresent() throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        Path earth = earthDir();
        Files.createDirectories(earth.resolve("region"));
        Files.writeString(earth.resolve(ManagedWorldAbort.MARKER_FILE_NAME), "staged\n");
        Files.writeString(earth.resolve("region/r.0.0.mca"), "fake region data");
        manifests.save(manifest(List.of()));

        assertThatThrownBy(() -> new ManagedWorldAbort(manifests).abort(serverRoot, serverRoot.resolve("world")))
                .isInstanceOf(IOException.class);
    }

    @Test void refusesWhenEntitiesDirectoryIsPresent() throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        Path earth = earthDir();
        Files.createDirectories(earth.resolve("entities"));
        Files.writeString(earth.resolve(ManagedWorldAbort.MARKER_FILE_NAME), "staged\n");
        manifests.save(manifest(List.of()));

        assertThatThrownBy(() -> new ManagedWorldAbort(manifests).abort(serverRoot, serverRoot.resolve("world")))
                .isInstanceOf(IOException.class);
    }

    @Test void refusesWhenPoiDirectoryIsPresent() throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        Path earth = earthDir();
        Files.createDirectories(earth.resolve("poi"));
        Files.writeString(earth.resolve(ManagedWorldAbort.MARKER_FILE_NAME), "staged\n");
        manifests.save(manifest(List.of()));

        assertThatThrownBy(() -> new ManagedWorldAbort(manifests).abort(serverRoot, serverRoot.resolve("world")))
                .isInstanceOf(IOException.class);
    }

    @Test void refusesWhenAnUnknownUnlistedFileIsPresent() throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        Path earth = earthDir();
        Files.createDirectories(earth);
        Files.writeString(earth.resolve(ManagedWorldAbort.MARKER_FILE_NAME), "staged\n");
        Files.writeString(earth.resolve("mystery.txt"), "not TerraForge's");
        manifests.save(manifest(List.of()));

        assertThatThrownBy(() -> new ManagedWorldAbort(manifests).abort(serverRoot, serverRoot.resolve("world")))
                .isInstanceOf(IOException.class);
        assertThat(Files.exists(earth)).isTrue();
    }

    @Test void refusesWhenNoManifestExists() {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        assertThatThrownBy(() -> new ManagedWorldAbort(manifests).abort(serverRoot, serverRoot.resolve("world")))
                .isInstanceOf(IOException.class);
    }

    @Test void refusesWhenEarthDirectoryIsASymlinkEscapingTheServerRoot(@TempDir Path outside) throws Exception {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        Path realTarget = outside.resolve("outside-earth");
        Files.createDirectories(realTarget);
        Files.writeString(realTarget.resolve(ManagedWorldAbort.MARKER_FILE_NAME), "staged\n");
        Files.createDirectories(serverRoot.resolve("world"));
        Path earth = earthDir();
        try {
            Files.createSymbolicLink(earth, realTarget);
        } catch (java.nio.file.FileSystemException e) {
            // Creating symlinks requires elevated privileges on some platforms (e.g. Windows without
            // developer mode); skip rather than fail the whole suite in that environment.
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink creation not permitted: " + e);
            return;
        }
        manifests.save(manifest(List.of()));

        assertThatThrownBy(() -> new ManagedWorldAbort(manifests).abort(serverRoot, serverRoot.resolve("world")))
                .isInstanceOf(IOException.class);
        assertThat(Files.exists(realTarget)).isTrue();
        assertThat(Files.exists(realTarget.resolve(ManagedWorldAbort.MARKER_FILE_NAME))).isTrue();
    }

    private Path pluginRoot() { return serverRoot.resolve("plugins/TerraForge"); }
    private Path earthDir() { return serverRoot.resolve("world/earth"); }

    private static ManagedWorldManifest manifest(List<String> extraOwnedFiles) {
        List<String> owned = new java.util.ArrayList<>(List.of("datapack/terraforge-world-height/pack.mcmeta"));
        owned.addAll(extraOwnedFiles);
        return new ManagedWorldManifest(1, ManagedWorldState.PENDING_RESTART, "earth", -64, 320,
                HASH, HASH, HASH, owned, "pending restart");
    }
}
