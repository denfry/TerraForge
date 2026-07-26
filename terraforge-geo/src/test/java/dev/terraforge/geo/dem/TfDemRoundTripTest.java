package dev.terraforge.geo.dem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.terraforge.core.data.ElevationProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The write/read round trip is the contract that everything downstream rests on: if a prepared tile
 * does not read back exactly as written, terrain is wrong in a way no later test would localise.
 */
class TfDemRoundTripTest {

    @RegisterExtension
    final ScratchDirectory scratch = new ScratchDirectory();

    private Path directory() {
        return scratch.path();
    }

    @Test
    void int16TileReadsBackExactly() throws IOException {
        DemTileKey key = new DemTileKey(50, 8);
        int side = 11;
        try (TfDemWriter writer = new TfDemWriter(directory(), TfDemHeader.int16(key, side, side))) {
            for (int y = 0; y < side; y++) {
                double[] row = new double[side];
                for (int x = 0; x < side; x++) {
                    row[x] = y * 100 + x;
                }
                writer.writeRow(row);
            }
        }

        MappedDemTile tile = MappedDemTile.map(directory().resolve("N50E008.tfdem"));

        assertThat(tile.key()).isEqualTo(key);
        assertThat(tile.width()).isEqualTo(side);
        assertThat(tile.height()).isEqualTo(side);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                assertThat(tile.sample(x, y)).isEqualTo(y * 100 + x);
            }
        }
    }

    @Test
    void float32TilePreservesFractionalMetres() throws IOException {
        DemTileKey key = new DemTileKey(-34, -59);
        try (TfDemWriter writer = new TfDemWriter(directory(), TfDemHeader.float32(key, 4, 4))) {
            for (int y = 0; y < 4; y++) {
                writer.writeRow(new double[]{-12.5, 0.25, 1234.75, Double.NaN});
            }
        }

        MappedDemTile tile = MappedDemTile.map(directory().resolve("S34W059.tfdem"));

        assertThat(tile.sample(0, 0)).isEqualTo(-12.5);
        assertThat(tile.sample(1, 0)).isEqualTo(0.25);
        assertThat(tile.sample(2, 0)).isEqualTo(1234.75);
        assertThat(ElevationProvider.isNoData(tile.sample(3, 0))).isTrue();
    }

    @Test
    void noDataSurvivesTheRoundTrip() throws IOException {
        DemTileKey key = new DemTileKey(0, 0);
        try (TfDemWriter writer = new TfDemWriter(directory(), TfDemHeader.int16(key, 3, 3))) {
            writer.writeRow(new double[]{Double.NaN, 5, Double.NaN});
            writer.writeRow(new double[]{5, 5, 5});
            writer.writeRow(new double[]{Double.NaN, 5, Double.NaN});
        }

        MappedDemTile tile = MappedDemTile.map(directory().resolve("N00E000.tfdem"));

        assertThat(ElevationProvider.isNoData(tile.sample(0, 0))).isTrue();
        assertThat(tile.sample(1, 1)).isEqualTo(5.0);
    }

    /** An interrupted preparation run must leave no file at all, never a half-written one. */
    @Test
    void anIncompleteTileIsNeverPublished() throws IOException {
        TfDemWriter writer = new TfDemWriter(directory(), TfDemHeader.int16(new DemTileKey(50, 8), 4, 4));
        writer.writeRow(new double[]{1, 2, 3, 4});

        assertThatThrownBy(writer::close)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("incomplete tile");

        assertThat(directory().resolve("N50E008.tfdem")).doesNotExist();
        assertThat(Files.list(directory())).isEmpty();
    }

    @Test
    void abortedTileLeavesNothingBehind() throws IOException {
        try (TfDemWriter writer = new TfDemWriter(directory(), TfDemHeader.int16(new DemTileKey(50, 8), 4, 4))) {
            writer.writeRow(new double[]{1, 2, 3, 4});
            writer.abort();
        }

        assertThat(Files.list(directory())).isEmpty();
    }

    @Test
    void aFileThatIsNotATfdemIsRejected() throws IOException {
        Path bogus = directory().resolve("N50E008.tfdem");
        Files.write(bogus, new byte[128]);

        assertThatThrownBy(() -> MappedDemTile.map(bogus))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a .tfdem");
    }

    @Test
    void elevationsBeyondInt16AreClampedRatherThanWrapped() throws IOException {
        DemTileKey key = new DemTileKey(50, 8);
        try (TfDemWriter writer = new TfDemWriter(directory(), TfDemHeader.int16(key, 2, 2))) {
            writer.writeRow(new double[]{99_000, -99_000});
            writer.writeRow(new double[]{0, 0});
        }

        MappedDemTile tile = MappedDemTile.map(directory().resolve("N50E008.tfdem"));

        assertThat(tile.sample(0, 0)).isEqualTo(Short.MAX_VALUE);
        assertThat(tile.sample(1, 0)).isEqualTo(Short.MIN_VALUE + 1);
    }
}
