package dev.terraforge.cli.dem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.terraforge.geo.dem.DemTileKey;
import dev.terraforge.geo.dem.MappedDemTile;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * GeoTIFF is the format the real datasets ship in, so the geo-referencing tags matter more than the
 * pixels: getting the tiepoint convention wrong shifts a whole country by half a pixel, silently.
 */
class GeoTiffRasterTest {

    @RegisterExtension
    final ScratchDirectory scratch = new ScratchDirectory();

    @Test
    void aPixelIsPointRasterPutsTheTiepointOnThePixelCentre() throws IOException {
        // 11x11 pixels at 0.1 degrees, north-west centre at (51, 8): exactly one degree cell.
        Path file = TestGeoTiff.of(11, 11, (row, column) -> (short) (column * 100))
                .origin(51.0, 8.0)
                .pixelSize(0.1)
                .writeTo(scratch.resolve("dem.tif"));

        try (SourceRaster raster = SourceRasters.open(file)) {
            assertThat(raster.bounds().maxLatitude()).isCloseTo(51.0, within(1e-9));
            assertThat(raster.bounds().minLongitude()).isCloseTo(8.0, within(1e-9));
            assertThat(raster.bounds().maxLongitude()).isCloseTo(9.0, within(1e-9));

            assertThat(raster.elevationAt(51.0, 8.0)).isCloseTo(0.0, within(1e-6));
            assertThat(raster.elevationAt(50.5, 8.5)).isCloseTo(500.0, within(1e-6));
            assertThat(raster.elevationAt(50.0, 9.0)).isCloseTo(1000.0, within(1e-6));
        }
    }

    @Test
    void aPixelIsAreaRasterShiftsByHalfAPixel() throws IOException {
        Path file = TestGeoTiff.of(10, 10, (row, column) -> (short) 100)
                .origin(51.0, 8.0)
                .pixelSize(0.1)
                .pixelIsArea()
                .writeTo(scratch.resolve("dem.tif"));

        try (SourceRaster raster = SourceRasters.open(file)) {
            // The first pixel centre sits half a pixel inside the declared corner.
            assertThat(raster.bounds().maxLatitude()).isCloseTo(50.95, within(1e-9));
            assertThat(raster.bounds().minLongitude()).isCloseTo(8.05, within(1e-9));
        }
    }

    @Test
    void aProjectedRasterIsRefusedWithAnActionableMessage() throws IOException {
        Path file = TestGeoTiff.of(4, 4, (row, column) -> (short) 1)
                .projected()
                .writeTo(scratch.resolve("utm.tif"));

        assertThatThrownBy(() -> SourceRasters.open(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("gdalwarp -t_srs EPSG:4326");
    }

    @Test
    void aRasterWithoutGeoreferencingIsRefused() throws IOException {
        Path file = TestGeoTiff.of(4, 4, (row, column) -> (short) 1)
                .withoutGeoreferencing()
                .writeTo(scratch.resolve("plain.tif"));

        assertThatThrownBy(() -> SourceRasters.open(file))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no geo-referencing");
    }

    @Test
    void sentinelValuesBecomeNoDataRatherThanTerrain() throws IOException {
        Path file = TestGeoTiff.of(3, 3, (row, column) -> (short) -32768)
                .origin(51.0, 8.0)
                .pixelSize(0.5)
                .writeTo(scratch.resolve("void.tif"));

        try (SourceRaster raster = SourceRasters.open(file)) {
            assertThat(raster.elevationAt(50.5, 8.5)).isNaN();
        }
    }

    @Test
    void aGeoTiffTranscodesIntoATile() throws IOException {
        Path input = scratch.resolve("in");
        TestGeoTiff.of(11, 11, (row, column) -> (short) (row * 50))
                .origin(51.0, 8.0)
                .pixelSize(0.1)
                .writeTo(input.resolve("copernicus.tif"));

        DemTranscoder.Result result = new DemTranscoder(
                scratch.resolve("out"), false, null, DemTranscoder.Encoding.AUTO, line -> { })
                .run(SourceRasters.find(input));

        assertThat(result.ok()).isTrue();
        assertThat(result.tilesWritten()).isEqualTo(1);

        MappedDemTile tile = MappedDemTile.map(scratch.resolve("out").resolve("N50E008.tfdem"));
        assertThat(tile.key()).isEqualTo(new DemTileKey(50, 8));
        assertThat(tile.interpolate(51.0, 8.5)).isCloseTo(0.0, within(1e-6));
        assertThat(tile.interpolate(50.0, 8.5)).isCloseTo(500.0, within(1e-6));
    }
}
