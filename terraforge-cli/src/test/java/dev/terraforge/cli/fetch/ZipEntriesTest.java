package dev.terraforge.cli.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipEntriesTest {

    @TempDir Path temporaryDirectory;

    @Test
    void extractsOnlyTheNamedEntry() throws Exception {
        Path archive = archive();
        Path target = temporaryDirectory.resolve("out/cities15000.txt");

        assertThat(ZipEntries.extract(archive, "cities15000.txt", target, false)).isTrue();
        assertThat(target).hasContent("Zurich");
        // The readme the publisher ships alongside it would not survive the gazetteer importer.
        assertThat(target.resolveSibling("readme.txt")).doesNotExist();
    }

    @Test
    void keepsAnAlreadyExtractedFile() throws Exception {
        Path archive = archive();
        Path target = temporaryDirectory.resolve("cities15000.txt");
        Files.writeString(target, "kept");

        assertThat(ZipEntries.extract(archive, "cities15000.txt", target, false)).isFalse();
        assertThat(target).hasContent("kept");
        assertThat(ZipEntries.extract(archive, "cities15000.txt", target, true)).isTrue();
        assertThat(target).hasContent("Zurich");
    }

    @Test
    void saysWhichEntryIsMissing() throws Exception {
        assertThatThrownBy(() -> ZipEntries.extract(archive(), "cities500.txt",
                temporaryDirectory.resolve("out.txt"), false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cities500.txt");
    }

    private Path archive() throws IOException {
        Path archive = temporaryDirectory.resolve("cities15000.zip");
        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("cities15000.txt"));
            zip.write("Zurich".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("licence".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return archive;
    }
}
