package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemDataFingerprintTest {

    @TempDir Path directory;

    @Test
    void sameContentsProduceTheSameFingerprint() throws Exception {
        Files.writeString(directory.resolve("a.dem"), "elevation-data");
        Files.createDirectories(directory.resolve("sub"));
        Files.writeString(directory.resolve("sub/b.dem"), "more-elevation-data");

        String first = DemDataFingerprint.of(directory);
        String second = DemDataFingerprint.of(directory);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64);
    }

    @Test
    void addingAFileChangesTheFingerprint() throws Exception {
        Files.writeString(directory.resolve("a.dem"), "elevation-data");
        String before = DemDataFingerprint.of(directory);

        Files.writeString(directory.resolve("b.dem"), "more-elevation-data");
        String after = DemDataFingerprint.of(directory);

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    void changingAFileSizeChangesTheFingerprint() throws Exception {
        Files.writeString(directory.resolve("a.dem"), "elevation-data");
        String before = DemDataFingerprint.of(directory);

        Files.writeString(directory.resolve("a.dem"), "elevation-data-with-more-bytes");
        String after = DemDataFingerprint.of(directory);

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    void renamingAFileChangesTheFingerprint() throws Exception {
        Files.writeString(directory.resolve("a.dem"), "elevation-data");
        String before = DemDataFingerprint.of(directory);

        Files.move(directory.resolve("a.dem"), directory.resolve("renamed.dem"));
        String after = DemDataFingerprint.of(directory);

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    void aMissingDirectoryFingerprintsAsEmpty() {
        Path missing = directory.resolve("does-not-exist");
        assertThat(DemDataFingerprint.of(missing)).isEqualTo(DemDataFingerprint.of(missing));
        assertThat(DemDataFingerprint.of(missing)).hasSize(64);
    }
}
