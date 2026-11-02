package dev.terraforge.geo.dem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class FileDemReaderTest {

    @TempDir
    Path demDirectory;

    /**
     * Enough tiles that the header reads are spread over several threads: the catalogue must be the
     * one a sequential scan builds, bad files skipped exactly as before.
     */
    @Test
    void aDirectoryCataloguedInParallelHoldsExactlyTheReadableTiles() throws IOException {
        Set<DemTileKey> expected = new HashSet<>();
        for (int latitude = 0; latitude < 30; latitude++) {
            for (int longitude = 0; longitude < 25; longitude++) {
                DemTileKey key = new DemTileKey(latitude, longitude);
                writeFlatTile(key);
                expected.add(key);
            }
        }
        Files.writeString(demDirectory.resolve("N40E000.tfdem"), "definitely not a raster");
        // A readable tile under the wrong name is skipped, not catalogued twice or under either key.
        Files.copy(demDirectory.resolve(new DemTileKey(0, 0).fileName()), demDirectory.resolve("N41E000.tfdem"));

        try (FileDemReader reader = FileDemReader.open(demDirectory)) {
            List<DemTileKey> catalogued = new ArrayList<>();
            reader.availableTiles().forEach(catalogued::add);

            assertThat(catalogued).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(catalogued).as("sorted by latitude, then longitude, as before").isSortedAccordingTo(
                    java.util.Comparator.comparingInt(DemTileKey::latDegree).thenComparingInt(DemTileKey::lonDegree));
            assertThat(reader.exists(new DemTileKey(40, 0))).isFalse();
            assertThat(reader.exists(new DemTileKey(41, 0))).isFalse();
        }
    }

    /**
     * A tile read again after the provider's cache evicted it must reuse the first mapping: mapping it
     * anew on every miss exhausted the kernel's limit on mappings during a planet-wide pregeneration.
     */
    @Test
    void aTileIsMappedOnceHoweverOftenItIsRead(
            // Windows cannot delete a file that is still mapped, and a mapping lives until a GC.
            @TempDir(cleanup = CleanupMode.NEVER) Path mappedDirectory) throws IOException {
        DemTileKey key = new DemTileKey(10, 20);
        writeFlatTile(mappedDirectory, key);

        try (FileDemReader reader = FileDemReader.open(mappedDirectory)) {
            DemTile first = reader.read(key).orElseThrow();
            for (int i = 0; i < 100; i++) {
                assertThat(reader.read(key).orElseThrow()).isSameAs(first);
            }
            assertThat(reader.read(new DemTileKey(11, 20))).isEmpty();
        }
    }

    private void writeFlatTile(DemTileKey key) throws IOException {
        writeFlatTile(demDirectory, key);
    }

    private static void writeFlatTile(Path directory, DemTileKey key) throws IOException {
        TfDemHeader header = TfDemHeader.int16(key, 2, 2);
        try (TfDemWriter writer = TfDemWriter.create(directory, header)) {
            for (int y = 0; y < header.height(); y++) {
                writer.writeRow(new double[]{100.0, 100.0});
            }
        }
    }
}
