package dev.terraforge.geo.dem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.terraforge.core.data.ElevationProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class TfDemRoundTripTest {

    private static final Offset<Double> METER = Offset.offset(1e-6);

    /**
     * Not cleaned up: a mapped tile keeps its file locked on Windows until the mapping is
     * collected, and Java 21 has no portable unmap. The tiles written here are a few hundred bytes.
     */
    @TempDir(cleanup = CleanupMode.NEVER)
    Path directory;

    @Test
    void writesAndReadsSamplesBackUnchanged() throws IOException {
        DemTileKey key = new DemTileKey(50, 8);
        double[][] grid = {
                {100.0, 200.0, 300.0},
                {110.0, 210.0, 310.0},
                {120.0, 220.0, 320.0},
        };
        writeTile(TfDemHeader.int16(key, 3, 3), grid);

        DemTile tile = MappedDemTile.open(directory.resolve(key.fileName()));
        assertThat(tile.key()).isEqualTo(key);
        assertThat(tile.width()).isEqualTo(3);
        assertThat(tile.height()).isEqualTo(3);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                assertThat(tile.sample(x, y)).isCloseTo(grid[y][x], METER);
            }
        }
        assertThat(tile.sizeBytes()).isEqualTo(TfDemFormat.HEADER_BYTES + 2L * 9);
    }

    @Test
    void rowZeroIsTheNorthEdge() throws IOException {
        DemTileKey key = new DemTileKey(50, 8);
        writeTile(TfDemHeader.int16(key, 2, 2), new double[][] {
                {1000.0, 1000.0},
                {0.0, 0.0},
        });

        DemTile tile = MappedDemTile.open(directory.resolve(key.fileName()));
        assertThat(tile.interpolate(51.0, 8.5)).isCloseTo(1000.0, METER);
        assertThat(tile.interpolate(50.0, 8.5)).isCloseTo(0.0, METER);
        assertThat(tile.interpolate(50.5, 8.5)).isCloseTo(500.0, METER);
    }

    @Test
    void interpolatesBilinearlyBetweenSamples() throws IOException {
        DemTileKey key = new DemTileKey(0, 0);
        writeTile(TfDemHeader.float32(key, 2, 2), new double[][] {
                {0.0, 100.0},
                {0.0, 100.0},
        });

        DemTile tile = MappedDemTile.open(directory.resolve(key.fileName()));
        assertThat(tile.interpolate(0.5, 0.25)).isCloseTo(25.0, METER);
        assertThat(tile.interpolate(0.5, 0.75)).isCloseTo(75.0, METER);
    }

    @Test
    void noDataNeighboursAreExcludedFromInterpolation() throws IOException {
        DemTileKey key = new DemTileKey(0, 0);
        writeTile(TfDemHeader.int16(key, 2, 2), new double[][] {
                {100.0, ElevationProvider.NO_DATA},
                {100.0, ElevationProvider.NO_DATA},
        });

        DemTile tile = MappedDemTile.open(directory.resolve(key.fileName()));
        // Halfway to a hole must stay at the real height, not sag toward a sentinel.
        assertThat(tile.interpolate(0.5, 0.5)).isCloseTo(100.0, METER);
        assertThat(tile.sample(1, 0)).isNaN();
    }

    @Test
    void allNeighboursMissingYieldsNoData() throws IOException {
        DemTileKey key = new DemTileKey(0, 0);
        writeTile(TfDemHeader.int16(key, 2, 2), new double[][] {
                {ElevationProvider.NO_DATA, ElevationProvider.NO_DATA},
                {ElevationProvider.NO_DATA, ElevationProvider.NO_DATA},
        });

        DemTile tile = MappedDemTile.open(directory.resolve(key.fileName()));
        assertThat(ElevationProvider.isNoData(tile.interpolate(0.5, 0.5))).isTrue();
    }

    @Test
    void float32TilesKeepSubMetrePrecisionAndNegativeDepths() throws IOException {
        DemTileKey key = new DemTileKey(-1, -1);
        writeTile(TfDemHeader.float32(key, 2, 2), new double[][] {
                {-1234.5, -1234.5},
                {-1234.5, -1234.5},
        });

        DemTile tile = MappedDemTile.open(directory.resolve(key.fileName()));
        assertThat(tile.sample(0, 0)).isCloseTo(-1234.5, Offset.offset(1e-3));
        assertThat(key.fileName()).isEqualTo("S01W001.tfdem");
    }

    @Test
    void headerRoundTripsExplicitBathymetryFlag() throws IOException {
        DemTileKey key = new DemTileKey(43, 6);
        TfDemHeader header = TfDemHeader.int16(key, 241, 241, true);

        TfDemHeader parsed = TfDemHeader.parse(header.toBuffer());

        assertThat(parsed.version()).isEqualTo(TfDemFormat.VERSION);
        assertThat(parsed.bathymetry()).isTrue();
        assertThat(parsed.encoding()).isEqualTo(TfDemFormat.ENCODING_INT16);
    }

    @Test
    void replacingAnExistingTilePublishesTheNewSamples() throws IOException {
        DemTileKey key = new DemTileKey(43, 6);
        writeTile(TfDemHeader.int16(key, 2, 2), new double[][] {{10.0, 10.0}, {10.0, 10.0}});
        writeTile(TfDemHeader.int16(key, 2, 2, true), new double[][] {{-20.0, -20.0}, {-20.0, -20.0}});

        DemTile tile = MappedDemTile.open(directory.resolve(key.fileName()));
        assertThat(tile.sample(0, 0)).isCloseTo(-20.0, METER);
        byte[] bytes = Files.readAllBytes(directory.resolve(key.fileName()));
        assertThat(TfDemHeader.parse(java.nio.ByteBuffer.wrap(bytes, 0, TfDemFormat.HEADER_BYTES)).bathymetry())
                .isTrue();
    }

    @Test
    void aShortTileIsNeverPublished() throws IOException {
        DemTileKey key = new DemTileKey(50, 8);
        TfDemWriter writer = TfDemWriter.create(directory, TfDemHeader.int16(key, 2, 2));
        writer.writeRow(new double[] {1.0, 2.0});

        assertThatThrownBy(writer::close).isInstanceOf(IOException.class).hasMessageContaining("1 of 2 rows");
        assertThat(directory.resolve(key.fileName())).doesNotExist();
        assertThat(Files.list(directory).toList()).isEmpty();
    }

    @Test
    void elevationsOutsideInt16RangeAreRejectedRatherThanWrapped() throws IOException {
        try (TfDemWriter writer = TfDemWriter.create(directory, TfDemHeader.int16(new DemTileKey(0, 0), 2, 2))) {
            assertThatThrownBy(() -> writer.writeRow(new double[] {40_000.0, 0.0}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("float32");
            writer.writeRow(new double[] {0.0, 0.0});
            writer.writeRow(new double[] {0.0, 0.0});
        }
    }

    private void writeTile(TfDemHeader header, double[][] rowsNorthToSouth) throws IOException {
        try (TfDemWriter writer = TfDemWriter.create(directory, header)) {
            for (double[] row : rowsNorthToSouth) {
                writer.writeRow(row);
            }
        }
    }
}
