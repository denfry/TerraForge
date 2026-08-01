package dev.terraforge.cli.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AbsentRegistryTest {

    @TempDir Path sourceRoot;

    private static final SourceCatalog.Download OCEAN = new SourceCatalog.Download(
            "dem", "Copernicus_DSM_COG_30_S40_00_W130_00_DEM.tif",
            URI.create("https://example.invalid/ocean.tif"));

    private static final SourceCatalog.Download LAND = new SourceCatalog.Download(
            "dem", "Copernicus_DSM_COG_30_N47_00_E008_00_DEM.tif",
            URI.create("https://example.invalid/land.tif"));

    @Test
    void anAbsenceSurvivesIntoTheNextRun() {
        AbsentRegistry first = AbsentRegistry.open(sourceRoot);
        assertThat(first.contains(OCEAN)).isFalse();
        first.record(OCEAN);

        AbsentRegistry second = AbsentRegistry.open(sourceRoot);

        assertThat(second.contains(OCEAN)).isTrue();
        assertThat(second.contains(LAND)).isFalse();
        assertThat(second.size()).isEqualTo(1);
    }

    /** Written as it goes, so a run killed half way keeps what it learned. */
    @Test
    void eachAbsenceIsOnDiskImmediately() throws Exception {
        AbsentRegistry registry = AbsentRegistry.open(sourceRoot);
        registry.record(OCEAN);

        assertThat(sourceRoot.resolve(AbsentRegistry.FILE_NAME))
                .content().contains("dem/Copernicus_DSM_COG_30_S40_00_W130_00_DEM.tif");
    }

    @Test
    void recordingTheSameFileTwiceWritesOneLine() throws Exception {
        AbsentRegistry registry = AbsentRegistry.open(sourceRoot);
        registry.record(OCEAN);
        registry.record(OCEAN);

        long entries = Files.readAllLines(sourceRoot.resolve(AbsentRegistry.FILE_NAME)).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .count();
        assertThat(entries).isEqualTo(1);
        assertThat(registry.size()).isEqualTo(1);
    }

    /** {@code --replace} must ask the publisher again rather than trusting last year's answer. */
    @Test
    void theDisabledRegistryRemembersAndRecordsNothing() {
        AbsentRegistry registry = AbsentRegistry.disabled();
        registry.record(OCEAN);

        assertThat(registry.contains(OCEAN)).isFalse();
        assertThat(sourceRoot.resolve(AbsentRegistry.FILE_NAME)).doesNotExist();
    }

    @Test
    void commentsAndBlankLinesAreNotEntries() throws Exception {
        Files.writeString(sourceRoot.resolve(AbsentRegistry.FILE_NAME),
                "# a comment\n\ndem/one.tif\n   \ndem/two.tif\n");

        assertThat(AbsentRegistry.open(sourceRoot).size()).isEqualTo(2);
    }

    /** A corrupt registry costs requests, never the download itself. */
    @Test
    void anUnreadableRegistryDegradesToRememberingNothing() throws Exception {
        Files.createDirectory(sourceRoot.resolve(AbsentRegistry.FILE_NAME));

        AbsentRegistry registry = AbsentRegistry.open(sourceRoot);

        assertThat(registry.size()).isZero();
        assertThat(registry.contains(OCEAN)).isFalse();
    }

}
