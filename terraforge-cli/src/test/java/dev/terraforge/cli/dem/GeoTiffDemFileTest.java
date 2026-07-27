package dev.terraforge.cli.dem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.terraforge.geo.dem.DemTileKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeoTiffDemFileTest {

    @TempDir
    Path temporaryDirectory;

    /** One degree cell, 0.5-degree pixels: a 2 x 2 raster covering N50 E008. */
    private Path singleCell(String noData, short... samples) throws IOException {
        Path file = temporaryDirectory.resolve("cell.tif");
        TestGeoTiff.write(file, 2, 2, 51.0, 8.0, 0.5, samples, noData);
        return file;
    }

    @Test
    void readsGeoreferencingAndSlicesTheRasterIntoDegreeCells() throws Exception {
        Path file = singleCell(null, (short) 100, (short) 200, (short) 300, (short) 400);

        try (GeoTiffDemFile raster = GeoTiffDemFile.open(file)) {
            assertThat(raster.tiles()).containsExactly(new DemTileKey(50, 8));
        }
    }

    @Test
    void aRasterSpanningSeveralCellsProducesOneTilePerCell() throws Exception {
        Path file = temporaryDirectory.resolve("wide.tif");
        // Two degrees wide, one high, at 0.5-degree pixels: N50 E008 and N50 E009.
        TestGeoTiff.write(file, 4, 2, 51.0, 8.0, 0.5,
                new short[]{1, 2, 3, 4, 5, 6, 7, 8}, null);

        try (GeoTiffDemFile raster = GeoTiffDemFile.open(file)) {
            assertThat(raster.tiles()).containsExactly(new DemTileKey(50, 8), new DemTileKey(50, 9));
        }
    }

    @Test
    void samplesLandOnTheRightCornersOfTheCell() throws Exception {
        Path file = singleCell(null, (short) 100, (short) 200, (short) 300, (short) 400);

        try (GeoTiffDemFile raster = GeoTiffDemFile.open(file)) {
            DemSource source = raster.sourceFor(new DemTileKey(50, 8));
            assertThat(source.width()).isEqualTo(3);
            assertThat(source.height()).isEqualTo(3);

            // North row of the cell comes from the north row of the raster: 100 west, 200 east.
            assertThat(source.readRow(0)).containsExactly(100.0, 200.0, 200.0);
            // South row: the south edge belongs to the last pixel rather than being a void.
            assertThat(source.readRow(2)).containsExactly(300.0, 400.0, 400.0);
        }
    }

    @Test
    void theDeclaredNoDataValueBecomesAVoid() throws Exception {
        Path file = singleCell("-32768", (short) 100, (short) -32768, (short) 300, (short) 400);

        try (GeoTiffDemFile raster = GeoTiffDemFile.open(file)) {
            double[] north = raster.sourceFor(new DemTileKey(50, 8)).readRow(0);
            assertThat(north[0]).isEqualTo(100.0);
            assertThat(north[1]).isNaN();
        }
    }

    @Test
    void areasTheRasterDoesNotCoverAreVoidsRatherThanZero() throws Exception {
        Path file = singleCell(null, (short) 100, (short) 200, (short) 300, (short) 400);

        try (GeoTiffDemFile raster = GeoTiffDemFile.open(file)) {
            assertThat(raster.tiles()).doesNotContain(new DemTileKey(50, 9));
            // Only the shared edge at longitude 9 has data; everything east of it is a void, so a
            // gap in coverage stays a gap instead of being filled with zeroes.
            double[] north = raster.sourceFor(new DemTileKey(50, 9)).readRow(0);
            assertThat(north[0]).isEqualTo(200.0);
            assertThat(north[1]).isNaN();
            assertThat(north[2]).isNaN();
        }
    }

    @Test
    void aRasterWithoutGeoreferencingIsRejectedWithTheCommandThatFixesIt() throws Exception {
        Path file = temporaryDirectory.resolve("plain.tif");
        Files.write(file, plainTiffWithoutGeoTags());

        assertThatThrownBy(() -> GeoTiffDemFile.open(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("gdalwarp");
    }

    @Test
    void aFileThatIsNotATiffIsRejected() throws Exception {
        Path file = temporaryDirectory.resolve("not-a-tiff.tif");
        Files.writeString(file, "this is not a raster");

        assertThatThrownBy(() -> GeoTiffDemFile.open(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("TIFF");
    }

    /** The same minimal TIFF, minus the ModelPixelScale and ModelTiepoint tags. */
    private byte[] plainTiffWithoutGeoTags() throws IOException {
        Path complete = singleCell(null, (short) 1, (short) 2, (short) 3, (short) 4);
        byte[] bytes = Files.readAllBytes(complete);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int ifd = buffer.getInt(4);
        int entries = Short.toUnsignedInt(buffer.getShort(ifd));
        for (int i = 0; i < entries; i++) {
            int base = ifd + 2 + i * 12;
            int tag = Short.toUnsignedInt(buffer.getShort(base));
            if (tag == 33550 || tag == 33922) {
                // Retag as a private tag nothing reads, keeping the directory length intact.
                buffer.putShort(base, (short) 65_000);
            }
        }
        return bytes;
    }
}
