package dev.terraforge.geo.landcover;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileLandcoverProviderTest {

    @TempDir
    Path directory;

    @Test
    void readsTheGridCoveringThePoint() throws IOException {
        writeCell(47, 8, LandcoverClass.TREE_COVER);
        writeCell(47, 9, LandcoverClass.GRASSLAND);

        try (FileLandcoverProvider provider = open(8)) {
            assertThat(provider.gridCount()).isEqualTo(2);
            assertThat(provider.landcoverAt(47.5, 8.5)).isEqualTo(LandcoverClass.TREE_COVER);
            assertThat(provider.landcoverAt(47.5, 9.5)).isEqualTo(LandcoverClass.GRASSLAND);
        }
    }

    @Test
    void aPointWithNoPreparedGridIsUnknownRatherThanWrong() throws IOException {
        writeCell(47, 8, LandcoverClass.TREE_COVER);

        try (FileLandcoverProvider provider = open(8)) {
            assertThat(provider.landcoverAt(10.0, 100.0)).isEqualTo(LandcoverClass.UNKNOWN);
        }
    }

    @Test
    void anEmptyDirectoryIsNotAnError() throws IOException {
        try (FileLandcoverProvider provider = open(8)) {
            assertThat(provider.gridCount()).isZero();
            assertThat(provider.landcoverAt(47.5, 8.5)).isEqualTo(LandcoverClass.UNKNOWN);
        }
    }

    @Test
    void aMissingDirectoryIsNotAnError() throws IOException {
        Path missing = directory.resolve("nothing-here");
        try (FileLandcoverProvider provider =
                     FileLandcoverProvider.open(missing, new CacheManager(64), 8)) {
            assertThat(provider.gridCount()).isZero();
            assertThat(provider.landcoverAt(47.5, 8.5)).isEqualTo(LandcoverClass.UNKNOWN);
        }
    }

    /**
     * The point of the lazy design: a directory far larger than the cache still answers every
     * lookup, because a grid is read when it is needed and evicted when it is not.
     */
    @Test
    void answersEveryCellWithACacheFarSmallerThanTheDirectory() throws IOException {
        for (int latitude = 0; latitude < 20; latitude++) {
            for (int longitude = 0; longitude < 20; longitude++) {
                writeCell(latitude, longitude,
                        (latitude + longitude) % 2 == 0 ? LandcoverClass.TREE_COVER : LandcoverClass.CROPLAND);
            }
        }
        try (FileLandcoverProvider provider = open(4)) {
            assertThat(provider.gridCount()).isEqualTo(400);
            for (int latitude = 0; latitude < 20; latitude++) {
                for (int longitude = 0; longitude < 20; longitude++) {
                    LandcoverClass expected = (latitude + longitude) % 2 == 0
                            ? LandcoverClass.TREE_COVER : LandcoverClass.CROPLAND;
                    assertThat(provider.landcoverAt(latitude + 0.5, longitude + 0.5))
                            .as("cell %d,%d", latitude, longitude)
                            .isEqualTo(expected);
                }
            }
        }
    }

    /** The catalogue is built from headers, so one broken file costs only itself. */
    @Test
    void anUnreadableGridIsSkippedRatherThanFatal() throws IOException {
        writeCell(47, 8, LandcoverClass.TREE_COVER);
        Files.writeString(directory.resolve("N47E009.tflc"), "not a grid at all");

        try (FileLandcoverProvider provider = open(8)) {
            assertThat(provider.gridCount()).isEqualTo(1);
            assertThat(provider.landcoverAt(47.5, 8.5)).isEqualTo(LandcoverClass.TREE_COVER);
        }
    }

    @Test
    void coverageSpansEveryPreparedGrid() throws IOException {
        writeCell(47, 8, LandcoverClass.TREE_COVER);
        writeCell(50, 12, LandcoverClass.GRASSLAND);

        try (FileLandcoverProvider provider = open(8)) {
            assertThat(provider.coverage().minLatitude()).isEqualTo(47.0);
            assertThat(provider.coverage().maxLatitude()).isEqualTo(51.0);
            assertThat(provider.coverage().minLongitude()).isEqualTo(8.0);
            assertThat(provider.coverage().maxLongitude()).isEqualTo(13.0);
        }
    }

    private FileLandcoverProvider open(int maxResidentGrids) throws IOException {
        return FileLandcoverProvider.open(directory, new CacheManager(64), maxResidentGrids);
    }

    private void writeCell(int latitude, int longitude, LandcoverClass value) throws IOException {
        LandcoverClass[] values = new LandcoverClass[4];
        Arrays.fill(values, value);
        LandcoverGridFile.write(directory.resolve(name(latitude, longitude)),
                latitude, longitude, latitude + 1, longitude + 1, 2, 2, values);
    }

    private static String name(int latitude, int longitude) {
        return String.format("%s%02d%s%03d.tflc",
                latitude < 0 ? "S" : "N", Math.abs(latitude),
                longitude < 0 ? "W" : "E", Math.abs(longitude));
    }
}
