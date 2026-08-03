package dev.terraforge.plugin.io;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafePathResolverTest {
    @TempDir Path directory;

    @Test
    void rejectsMissingDescendantsBelowAnEscapingSymlink() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.abort("symbolic links unavailable: " + exception.getMessage());
        }

        assertThatThrownBy(() -> SafePathResolver.resolve(root, Path.of("link", "new", "file.json")))
                .isInstanceOf(IOException.class);
    }
}
