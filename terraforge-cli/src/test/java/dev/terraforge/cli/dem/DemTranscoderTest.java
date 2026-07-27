package dev.terraforge.cli.dem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.geo.dem.DemTile;
import dev.terraforge.geo.dem.DemTileKey;
import dev.terraforge.geo.dem.MappedDemTile;
import dev.terraforge.geo.dem.TfDemFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class DemTranscoderTest {

    /** Mapped tiles stay locked on Windows until collected; see TfDemRoundTripTest in geo. */
    @TempDir(cleanup = CleanupMode.NEVER)
    Path workDirectory;

    @Test
    void transcodesAnHgtTileIntoASampleIdenticalTfdemTile() throws IOException {
        short[][] grid = {
                {100, 200, 300},
                {110, 210, 310},
                {120, 220, HgtFixtures.VOID},
        };
        Path hgt = HgtFixtures.write(workDirectory, "N50E008.hgt", grid);

        DemTranscoder.Result result;
        try (HgtDemSource source = HgtDemSource.open(hgt)) {
            assertThat(source.key()).isEqualTo(new DemTileKey(50, 8));
            assertThat(source.width()).isEqualTo(3);
            result = new DemTranscoder(workDirectory.resolve("out"), TfDemFormat.ENCODING_INT16, false)
                    .transcode(source);
        }

        assertThat(result.samples()).isEqualTo(9);
        assertThat(result.voids()).isEqualTo(1);
        assertThat(result.voidPercentage()).isCloseTo(11.11, Offset.offset(0.01));

        DemTile tile = MappedDemTile.open(result.file());
        assertThat(tile.sample(0, 0)).isEqualTo(100.0);
        assertThat(tile.sample(2, 1)).isEqualTo(310.0);
        assertThat(ElevationProvider.isNoData(tile.sample(2, 2))).isTrue();
    }

    @Test
    void existingTilesAreLeftAloneUnlessOverwriteIsGiven() throws IOException {
        Path hgt = HgtFixtures.write(workDirectory, "N50E008.hgt", new short[][] {{1, 2}, {3, 4}});
        Path out = workDirectory.resolve("out");

        try (HgtDemSource source = HgtDemSource.open(hgt)) {
            assertThat(new DemTranscoder(out, TfDemFormat.ENCODING_INT16, false).transcode(source).skipped())
                    .isFalse();
        }
        try (HgtDemSource source = HgtDemSource.open(hgt)) {
            assertThat(new DemTranscoder(out, TfDemFormat.ENCODING_INT16, false).transcode(source).skipped())
                    .isTrue();
        }
        try (HgtDemSource source = HgtDemSource.open(hgt)) {
            assertThat(new DemTranscoder(out, TfDemFormat.ENCODING_INT16, true).transcode(source).skipped())
                    .isFalse();
        }
    }

    @Test
    void aMisnamedHgtFileIsRejectedBeforeAnythingIsWritten() throws IOException {
        Path hgt = HgtFixtures.write(workDirectory, "elevation.hgt", new short[][] {{1, 2}, {3, 4}});

        assertThatThrownBy(() -> HgtDemSource.open(hgt))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("N50E008.hgt");
    }

    @Test
    void aNonSquareHgtFileIsRejected() throws IOException {
        Path hgt = workDirectory.resolve("N50E008.hgt");
        Files.write(hgt, new byte[] {0, 1, 0, 2, 0, 3});

        assertThatThrownBy(() -> HgtDemSource.open(hgt))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("square");
    }

    @Test
    void encodingNamesMapToFormatConstants() {
        assertThat(DemTranscoder.parseEncoding("int16")).isEqualTo(TfDemFormat.ENCODING_INT16);
        assertThat(DemTranscoder.parseEncoding("FLOAT32")).isEqualTo(TfDemFormat.ENCODING_FLOAT32);
        assertThatThrownBy(() -> DemTranscoder.parseEncoding("int8"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Writes raw SRTM-style tiles: big-endian int16, square, no header. */
    private static final class HgtFixtures {

        static final short VOID = -32768;

        static Path write(Path directory, String name, short[][] rowsNorthToSouth) throws IOException {
            int size = rowsNorthToSouth.length;
            ByteBuffer buffer = ByteBuffer.allocate(size * size * 2).order(ByteOrder.BIG_ENDIAN);
            for (short[] row : rowsNorthToSouth) {
                for (short value : row) {
                    buffer.putShort(value);
                }
            }
            Path file = directory.resolve(name);
            Files.write(file, buffer.array());
            return file;
        }
    }
}
