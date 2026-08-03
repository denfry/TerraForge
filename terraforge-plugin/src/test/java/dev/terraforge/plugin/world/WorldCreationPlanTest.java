package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.config.VerticalProfile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldCreationPlanTest {

    @TempDir Path temporaryDirectory;
    @Test void returnsAllPreflightFailuresWithoutWriting() throws Exception {
        java.nio.file.Files.createDirectories(temporaryDirectory.resolve("worlds/earth"));
        Path demDirectory = temporaryDirectory.resolve("dem");
        ManagedWorldEnvironment environment = new ManagedWorldEnvironment() {
            public boolean isOfficialSupportedPaper() { return false; } public boolean isWorldLoaded(String name) { return true; }
            public boolean hasPreparedDem() { return false; } public Path serverRoot() { return temporaryDirectory; }
            public Path worldContainer() { return temporaryDirectory.resolve("worlds"); } public long usableDiskBytes(Path path) { return 0; }
        };
        WorldCreationPlan plan = new ManagedWorldService().plan(environment, "spawn", 10, VerticalProfile.regional(),
                demDirectory, new PaperWorldSettingsEditor.ChunkSettings(6000, 24, "10s"));
        assertThat(plan.executable()).isFalse();
        assertThat(plan.checks()).allMatch(check -> !check.passed());
        assertThat(plan.configFingerprint()).isEqualTo(VerticalProfile.regional().fingerprint());
        assertThat(plan.dataFingerprint()).isEqualTo(DemDataFingerprint.of(demDirectory));
    }
}
