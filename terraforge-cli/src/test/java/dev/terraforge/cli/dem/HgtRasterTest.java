package dev.terraforge.cli.dem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class HgtRasterTest {

    @RegisterExtension
    final ScratchDirectory scratch = new ScratchDirectory();

    /** Writes an HGT whose samples are produced by {@code value(row, column)}, north row first. */
    private Path writeHgt(String name, int side, SampleFunction value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(side * side * 2).order(ByteOrder.BIG_ENDIAN);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                buffer.putShort(value.at(y, x));
            }
        }
        Path file = scratch.resolve(name);
        Files.write(file, buffer.array());
        return file;
    }

    @FunctionalInterface
    private interface SampleFunction {
        short at(int row, int column);
    }

    @Test
    void cornersLandOnTheDegreeCellCorners() throws IOException {
        // 0 in the north-west, rising 100 m per column eastward.
        Path file = writeHgt("N50E008.hgt", 11, (row, column) -> (short) (column * 100));

        try (SourceRaster raster = SourceRasters.open(file)) {
            assertThat(raster.bounds().minLatitude()).isCloseTo(50.0, within(1e-9));
            assertThat(raster.bounds().maxLatitude()).isCloseTo(51.0, within(1e-9));
            assertThat(raster.bounds().minLongitude()).isCloseTo(8.0, within(1e-9));
            assertThat(raster.bounds().maxLongitude()).isCloseTo(9.0, within(1e-9));

            assertThat(raster.elevationAt(51.0, 8.0)).isCloseTo(0.0, within(1e-6));
            assertThat(raster.elevationAt(51.0, 9.0)).isCloseTo(1000.0, within(1e-6));
            assertThat(raster.elevationAt(50.5, 8.5)).isCloseTo(500.0, within(1e-6));
        }
    }

    @Test
    void voidsStayNoDataRatherThanBecomingAThirtyKilometrePit() throws IOException {
        Path file = writeHgt("N50E008.hgt", 3, (row, column) -> HgtRaster.VOID);

        try (SourceRaster raster = SourceRasters.open(file)) {
            assertThat(raster.elevationAt(50.5, 8.5)).isNaN();
        }
    }

    @Test
    void aSingleVoidDoesNotContaminateItsNeighbours() throws IOException {
        Path file = writeHgt("N50E008.hgt", 2,
                (row, column) -> row == 0 && column == 0 ? HgtRaster.VOID : (short) 200);

        try (SourceRaster raster = SourceRasters.open(file)) {
            assertThat(raster.elevationAt(50.5, 8.5)).isCloseTo(200.0, within(1e-6));
        }
    }

    @Test
    void pointsOutsideTheCellHaveNoData() throws IOException {
        Path file = writeHgt("N50E008.hgt", 3, (row, column) -> (short) 100);

        try (SourceRaster raster = SourceRasters.open(file)) {
            assertThat(raster.elevationAt(52.0, 8.5)).isNaN();
            assertThat(raster.elevationAt(50.5, 20.0)).isNaN();
        }
    }

    @Test
    void southernAndWesternCellsParseFromTheFileName() {
        assertThat(HgtRaster.parseCorner("S34W059.hgt")).containsExactly(-34, -59);
        assertThat(HgtRaster.parseCorner("N00E000.hgt")).containsExactly(0, 0);
        assertThat(HgtRaster.parseCorner("N50E008.SRTMGL1.hgt")).containsExactly(50, 8);
        assertThat(HgtRaster.parseCorner("elevation.hgt")).isNull();
    }

    @Test
    void anUnnamedTileIsRejectedWithAnExplanation() throws IOException {
        Path file = writeHgt("elevation.hgt", 3, (row, column) -> (short) 1);

        assertThatThrownBy(() -> SourceRasters.open(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("named like N50E008.hgt");
    }

    @Test
    void aNonSquareFileIsRejected() throws IOException {
        Path file = scratch.resolve("N50E008.hgt");
        Files.write(file, new byte[52]); // 26 samples: one more than the 5x5 it almost is

        assertThatThrownBy(() -> SourceRasters.open(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not square");
    }

    @Test
    void resolutionIsReportedInMetres() throws IOException {
        Path file = writeHgt("N50E008.hgt", 3601, (row, column) -> (short) 0);

        try (SourceRaster raster = SourceRasters.open(file)) {
            // One arc-second is about 30 m north-south, less east-west at 50 degrees north.
            assertThat(raster.resolutionMeters()).isBetween(15.0, 31.0);
            assertThat(raster.needsFloatPrecision()).isFalse();
        }
    }
}
