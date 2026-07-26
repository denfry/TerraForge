package dev.terraforge.cli.dem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * A per-test temporary directory whose cleanup is best effort.
 *
 * <p>Same reasoning as the geo module's copy: HGT sources and prepared tiles are memory-mapped, and
 * Java cannot unmap on demand before JDK 22, so strict deletion would fail on Windows for tests that
 * actually passed.
 */
final class ScratchDirectory implements BeforeEachCallback, AfterEachCallback {

    private Path path;

    Path path() {
        return path;
    }

    Path resolve(String name) {
        return path.resolve(name);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws IOException {
        path = Files.createTempDirectory("terraforge-cli-test");
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (path == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException ignored) {
                    // still mapped; the OS reclaims it when the JVM exits
                }
            });
        } catch (IOException ignored) {
            // nothing worth failing a test over
        }
    }
}
