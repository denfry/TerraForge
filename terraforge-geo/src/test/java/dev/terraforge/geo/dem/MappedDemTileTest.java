package dev.terraforge.geo.dem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.terraforge.core.data.ElevationProvider;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/** Sampling and interpolation, on tiles built in memory so no file is touched. */
class MappedDemTileTest {

    private static final DemTileKey KEY = new DemTileKey(50, 8);

    /** A tile whose samples are supplied row-major from the north-west corner. */
    private static MappedDemTile tile(int width, int height, double... metres) {
        TfDemHeader header = TfDemHeader.int16(KEY, width, height);
        ByteBuffer buffer = ByteBuffer.allocate(width * height * 2).order(ByteOrder.BIG_ENDIAN);
        for (double value : metres) {
            buffer.putShort(Double.isNaN(value) ? TfDemFormat.INT16_NO_DATA : (short) value);
        }
        return MappedDemTile.wrap(header, buffer.flip());
    }

    @Test
    void cornersMapToTheTileEdges() {
        MappedDemTile tile = tile(2, 2,
                10, 20,
                30, 40);

        // North-west, north-east, south-west, south-east.
        assertThat(tile.interpolate(51.0, 8.0)).isEqualTo(10.0);
        assertThat(tile.interpolate(51.0, 9.0)).isEqualTo(20.0);
        assertThat(tile.interpolate(50.0, 8.0)).isEqualTo(30.0);
        assertThat(tile.interpolate(50.0, 9.0)).isEqualTo(40.0);
    }

    @Test
    void theCentreIsTheMeanOfFourCorners() {
        MappedDemTile tile = tile(2, 2,
                0, 100,
                200, 300);

        assertThat(tile.interpolate(50.5, 8.5)).isCloseTo(150.0, within(1e-9));
    }

    @Test
    void interpolationIsLinearAlongAnEdge() {
        MappedDemTile tile = tile(3, 3,
                0, 50, 100,
                0, 50, 100,
                0, 50, 100);

        assertThat(tile.interpolate(50.5, 8.25)).isCloseTo(25.0, within(1e-9));
        assertThat(tile.interpolate(50.5, 8.75)).isCloseTo(75.0, within(1e-9));
    }

    @Test
    void pointsOutsideTheTileHaveNoData() {
        MappedDemTile tile = tile(2, 2, 1, 1, 1, 1);

        assertThat(ElevationProvider.isNoData(tile.interpolate(49.5, 8.5))).isTrue();
        assertThat(ElevationProvider.isNoData(tile.interpolate(50.5, 7.5))).isTrue();
    }

    /** A single void must not spread across the three good samples around it. */
    @Test
    void aVoidNeighbourIsExcludedFromTheMeanRatherThanPropagated() {
        MappedDemTile tile = tile(2, 2,
                Double.NaN, 100,
                100, 100);

        double centre = tile.interpolate(50.5, 8.5);

        assertThat(ElevationProvider.isNoData(centre)).isFalse();
        assertThat(centre).isCloseTo(100.0, within(1e-9));
    }

    @Test
    void anEntirelyVoidNeighbourhoodStaysNoData() {
        MappedDemTile tile = tile(2, 2, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

        assertThat(ElevationProvider.isNoData(tile.interpolate(50.5, 8.5))).isTrue();
    }

    @Test
    void outOfRangeIndicesAreNoDataRatherThanAnException() {
        MappedDemTile tile = tile(2, 2, 1, 2, 3, 4);

        assertThat(ElevationProvider.isNoData(tile.sample(-1, 0))).isTrue();
        assertThat(ElevationProvider.isNoData(tile.sample(0, 2))).isTrue();
    }

    @Test
    void sizeBytesCoversHeaderAndSamples() {
        MappedDemTile tile = tile(2, 2, 1, 2, 3, 4);

        assertThat(tile.sizeBytes()).isEqualTo(TfDemFormat.HEADER_BYTES + 8);
    }
}
