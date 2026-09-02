package dev.terraforge.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildProvenanceTest {

    @TempDir Path directory;

    @Test
    void theGeneratedResourceOnTheClasspathIsParsed() {
        BuildProvenance provenance = BuildProvenance.load();

        // processResources expanded the resource into build/resources/main, so the placeholders must
        // be gone: a literal "${commit}" here would mean the filtering silently stopped working.
        assertThat(provenance.commit()).doesNotContain("$").isNotBlank();
        assertThat(provenance.commit()).matches("[0-9a-f]{40}|unknown");
        assertThat(provenance.version()).doesNotContain("$").isNotBlank();
        assertThat(provenance.dirtyState()).isIn("true", "false", "unknown");
        assertThat(provenance.describe()).isNotBlank();
    }

    @Test
    void aMissingResourceYieldsUnknownProvenanceInsteadOfThrowing() {
        BuildProvenance provenance = BuildProvenance.from(null);

        assertThat(provenance.commit()).isEqualTo("unknown");
        assertThat(provenance.commitKnown()).isFalse();
        assertThat(provenance.dirty()).isFalse();
        assertThat(provenance.describe()).contains("no git metadata");
    }

    @Test
    void aMalformedResourceYieldsUnknownProvenance() {
        // Not a properties file at all -- Properties.load rejects a stray backslash escape.
        BuildProvenance provenance = BuildProvenance.from(
                new ByteArrayInputStream("commit=\\uZZZZ".getBytes(StandardCharsets.UTF_8)));

        assertThat(provenance.commitKnown()).isFalse();
    }

    @Test
    void aDirtyBuildIsReportedAsDirty() {
        BuildProvenance provenance = BuildProvenance.from(new ByteArrayInputStream(
                "version=1.2.3\ncommit=39984e0aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\ndirty=true\n"
                        .getBytes(StandardCharsets.UTF_8)));

        assertThat(provenance.dirty()).isTrue();
        assertThat(provenance.describe()).isEqualTo("1.2.3 commit 39984e0 (DIRTY)");
    }

    @Test
    void aCleanBuildIsReportedAsClean() {
        BuildProvenance provenance = BuildProvenance.from(new ByteArrayInputStream(
                "version=1.2.3\ncommit=39984e0aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\ndirty=false\n"
                        .getBytes(StandardCharsets.UTF_8)));

        assertThat(provenance.dirty()).isFalse();
        assertThat(provenance.commitKnown()).isTrue();
        assertThat(provenance.describe()).isEqualTo("1.2.3 commit 39984e0 (clean)");
    }

    @Test
    void anUnknownDirtyFlagIsNotReportedAsClean() {
        BuildProvenance provenance = BuildProvenance.from(new ByteArrayInputStream(
                "version=1.2.3\ncommit=39984e0aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\ndirty=unknown\n"
                        .getBytes(StandardCharsets.UTF_8)));

        assertThat(provenance.describe()).doesNotContain("clean").contains("unknown");
    }

    @Test
    void sha256MatchesTheKnownDigestOfKnownBytes() throws Exception {
        Path file = directory.resolve("known.jar");
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));

        assertThat(BuildProvenance.sha256(file.toFile()))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sha256OfAnEmptyFileMatchesTheKnownEmptyDigest() throws Exception {
        Path file = directory.resolve("empty.jar");
        Files.write(file, new byte[0]);

        assertThat(BuildProvenance.sha256(file.toFile()))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void sha256SpansMoreThanOneReadBuffer() throws Exception {
        // 192 KiB of a repeating pattern crosses the 64 KiB digest buffer, so a chunking bug in the
        // streamed read would change the digest away from the single-shot value.
        byte[] payload = new byte[192 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        Path file = directory.resolve("large.jar");
        Files.write(file, payload);

        byte[] expected = java.security.MessageDigest.getInstance("SHA-256").digest(payload);
        assertThat(BuildProvenance.sha256(file.toFile()))
                .isEqualTo(java.util.HexFormat.of().formatHex(expected));
    }

    @Test
    void sha256OfAnUnreadableFileIsUnavailable() {
        assertThat(BuildProvenance.sha256(directory.resolve("does-not-exist.jar").toFile()))
                .isEqualTo("unavailable");
        assertThat(BuildProvenance.sha256(null)).isEqualTo("unavailable");
    }
}
