package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldCreationPlanTest {
    @TempDir Path temporaryDirectory;
    @Test void returnsAllPreflightFailuresWithoutWriting() throws Exception {
        java.nio.file.Files.createDirectories(temporaryDirectory.resolve("worlds/earth"));
        ManagedWorldEnvironment environment = new ManagedWorldEnvironment() {
            public boolean isOfficialSupportedPaper() { return false; } public boolean isWorldLoaded(String name) { return true; }
            public boolean hasPreparedDem() { return false; } public Path serverRoot() { return temporaryDirectory; }
            public Path worldContainer() { return temporaryDirectory.resolve("worlds"); } public long usableDiskBytes(Path path) { return 0; }
        };
        WorldCreationPlan plan = new ManagedWorldService().plan(environment, "spawn", 10);
        assertThat(plan.executable()).isFalse();
        assertThat(plan.checks()).allMatch(check -> !check.passed());
    }
}
