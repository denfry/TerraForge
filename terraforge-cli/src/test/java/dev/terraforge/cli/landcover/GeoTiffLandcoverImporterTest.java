package dev.terraforge.cli.landcover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.terraforge.cli.dem.TestGeoTiff;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.geo.landcover.LandcoverGridFile;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeoTiffLandcoverImporterTest {

    @TempDir Path temporaryDirectory;

    /** Four pixels per degree, so a two-degree raster is eight pixels across. */
    private static final int PIXELS_PER_DEGREE = 4;

    @Test
    void slicesARasterIntoOneGridPerDegreeCell() throws Exception {
        // Two cells side by side: the western one all tree cover, the eastern one all cropland.
        Path raster = raster(2, 1, 48.0, 8.0, code -> code < PIXELS_PER_DEGREE ? 10 : 40);
        Path output = temporaryDirectory.resolve("prepared");

        var result = GeoTiffLandcoverImporter.importFile(raster, output, 2, null, false);

        assertThat(result.written()).extracting(path -> path.getFileName().toString())
                .containsExactlyInAnyOrder("N47E008.tflc", "N47E009.tflc");
        assertThat(LandcoverGridFile.read(output.resolve("N47E008.tflc")).landcoverAt(47.5, 8.5))
                .isEqualTo(LandcoverClass.TREE_COVER);
        assertThat(LandcoverGridFile.read(output.resolve("N47E009.tflc")).landcoverAt(47.5, 9.5))
                .isEqualTo(LandcoverClass.CROPLAND);
    }

    @Test
    void writesAGridThatCoversExactlyItsDegreeCell() throws Exception {
        Path raster = raster(1, 1, 48.0, 8.0, column -> 30);

        GeoTiffLandcoverImporter.importFile(raster, temporaryDirectory.resolve("prepared"), 4, null, false);

        var grid = LandcoverGridFile.read(temporaryDirectory.resolve("prepared/N47E008.tflc"));
        assertThat(grid.landcoverAt(47.001, 8.001)).isEqualTo(LandcoverClass.GRASSLAND);
        assertThat(grid.landcoverAt(47.999, 8.999)).isEqualTo(LandcoverClass.GRASSLAND);
        assertThat(grid.landcoverAt(46.999, 8.5)).isEqualTo(LandcoverClass.UNKNOWN);
        assertThat(grid.landcoverAt(48.001, 8.5)).isEqualTo(LandcoverClass.UNKNOWN);
    }

    @Test
    void importsOnlyTheCellsTheClipTouches() throws Exception {
        Path raster = raster(2, 1, 48.0, 8.0, column -> 10);
        Path output = temporaryDirectory.resolve("prepared");

        var result = GeoTiffLandcoverImporter.importFile(raster, output, 2,
                new GeoBounds(47.1, 9.2, 47.9, 9.8), false);

        assertThat(result.written()).extracting(path -> path.getFileName().toString())
                .containsExactly("N47E009.tflc");
    }

    @Test
    void keepsGridsThatAreAlreadyPrepared() throws Exception {
        Path raster = raster(1, 1, 48.0, 8.0, column -> 10);
        Path output = temporaryDirectory.resolve("prepared");
        GeoTiffLandcoverImporter.importFile(raster, output, 2, null, false);

        var second = GeoTiffLandcoverImporter.importFile(raster, output, 2, null, false);

        assertThat(second.written()).isEmpty();
        assertThat(second.skipped()).containsExactly("N47E008 already prepared");
    }

    @Test
    void refusesAResolutionThatDoesNotDivideTheSource() throws Exception {
        Path raster = raster(1, 1, 48.0, 8.0, column -> 10);

        assertThatThrownBy(() -> GeoTiffLandcoverImporter.importFile(
                raster, temporaryDirectory.resolve("prepared"), 3, null, false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("4 pixels per degree");
    }

    @Test
    void skipsADegreeCellTheRasterOnlyPartlyCovers() throws Exception {
        // Half a degree of raster: no complete cell, so no grid rather than a half-empty one.
        Path raster = rasterOfPixels(2, 2, 47.5, 8.0, column -> 10);

        var result = GeoTiffLandcoverImporter.importFile(
                raster, temporaryDirectory.resolve("prepared"), 2, null, false);

        assertThat(result.written()).isEmpty();
        assertThat(result.skipped()).anyMatch(reason -> reason.contains("only partly covered"));
    }

    /** @param degreesWide raster width in whole degrees */
    private Path raster(int degreesWide, int degreesTall, double originLat, double originLon,
                        ColumnCode code) throws IOException {
        return rasterOfPixels(degreesWide * PIXELS_PER_DEGREE, degreesTall * PIXELS_PER_DEGREE,
                originLat, originLon, code);
    }

    private Path rasterOfPixels(int width, int height, double originLat, double originLon, ColumnCode code)
            throws IOException {
        byte[] samples = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                samples[y * width + x] = (byte) code.at(x);
            }
        }
        Path file = temporaryDirectory.resolve("landcover-" + width + "x" + height + "-"
                + originLat + "-" + originLon + ".tif");
        TestGeoTiff.writeBytes(file, width, height, originLat, originLon,
                1.0 / PIXELS_PER_DEGREE, samples);
        return file;
    }

    private interface ColumnCode {
        int at(int column);
    }
}
