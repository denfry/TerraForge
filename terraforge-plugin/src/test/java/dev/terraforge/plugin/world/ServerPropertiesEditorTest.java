package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerPropertiesEditorTest {
    @TempDir Path temporaryDirectory;
    @Test void preservesCommentsAndUnrelatedKeys() throws Exception {
        Path file = temporaryDirectory.resolve("server.properties");
        Files.writeString(file, "# kept\r\nlevel-name=spawn\r\nview-distance=10\r\n");
        var edit = new ServerPropertiesEditor().plan(file, "earth");
        assertThat(new String(edit.replacement())).isEqualTo("# kept\r\nlevel-name=earth\r\nview-distance=10\r\n");
        assertThat(Files.readString(file)).contains("level-name=spawn");
    }
}
