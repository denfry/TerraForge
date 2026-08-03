package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperWorldSettingsEditorTest {
    @TempDir Path temporaryDirectory;
    @Test void changesOnlyTheApprovedChunkSettings() throws Exception {
        Path file = temporaryDirectory.resolve("earth/paper-world.yml"); Files.createDirectories(file.getParent());
        Files.writeString(file, "entities:\n  spawning: true\nchunks:\n  keep-spawn-loaded: true\n");
        var edit = new PaperWorldSettingsEditor().plan(file, new PaperWorldSettingsEditor.ChunkSettings(6000, 24, "10s"));
        String output = new String(edit.replacement(), StandardCharsets.UTF_8);
        assertThat(output).contains("entities:", "spawning: true", "keep-spawn-loaded: true", "auto-save-interval: 6000", "max-auto-save-chunks-per-tick: 24", "delay-chunk-unloads-by: \"10s\"");
        assertThat(Files.readString(file)).doesNotContain("auto-save-interval");
    }
}
