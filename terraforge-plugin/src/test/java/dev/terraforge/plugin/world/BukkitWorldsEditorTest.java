package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BukkitWorldsEditorTest {
    @TempDir Path temporaryDirectory;
    @Test void addsEarthWithoutChangingSpawn() throws Exception {
        Path file = temporaryDirectory.resolve("bukkit.yml");
        Files.writeString(file, "worlds:\n  spawn:\n    generator: Other\n    keep-spawn-in-memory: true\n");
        PlannedEdit edit = new BukkitWorldsEditor().plan(file);
        String output = new String(edit.replacement(), StandardCharsets.UTF_8);
        assertThat(output).contains("spawn:", "generator: \"Other\"", "keep-spawn-in-memory: true", "earth:", "generator: \"TerraForge\"");
        assertThat(Files.readString(file)).doesNotContain("TerraForge");
    }
}
