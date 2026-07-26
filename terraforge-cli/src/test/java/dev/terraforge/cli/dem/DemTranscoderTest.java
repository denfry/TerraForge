package dev.terraforge.cli.dem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.geo.dem.DemTileKey;
import dev.terraforge.geo.dem.MappedDemTile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * End to end for the preparation half: source rasters in, {@code .tfdem} tiles out, sampled back
 * through the same reader the server uses.
 */
class DemTranscoderTest {

    @RegisterExtension
    final ScratchDirectory scratch = new ScratchDirectory();

    private final List<String> log = new ArrayList<>();

    private Path input() throws IOException {
        return Files.createDirectories(scratch.resolve("in"));
    }

    private Path output() {
        return scratch.resolve("out");
    }

    private Path writeHgt(String name, int side, HgtSamples samples) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(side * side * 2).order(ByteOrder.BIG_ENDIAN);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                buffer.putShort(samples.at(y, x));
            }
        }
        Path file = input().resolve(name);
        Files.write(file, buffer.array());
        return file;
    }

    @FunctionalInterface
    private interface HgtSamples {
        short at(int row, int column);
    }

    private DemTranscoder transcoder(boolean overwrite, Integer side) {
        return new DemTranscoder(output(), overwrite, side, DemTranscoder.Encoding.AUTO, log::add);
    }

    @Test
    void anHgtTileBecomesATfdemTileWithTheSameElevations() throws IOException {
        writeHgt("N50E008.hgt", 11, (row, column) -> (short) (column * 100));

        DemTranscoder.Result result = transcoder(false, null).run(SourceRasters.find(input()));

        assertThat(result.ok()).isTrue();
        assertThat(result.tilesWritten()).isEqualTo(1);

        MappedDemTile tile = MappedDemTile.map(output().resolve("N50E008.tfdem"));
        assertThat(tile.key()).isEqualTo(new DemTileKey(50, 8));
        assertThat(tile.width()).isEqualTo(11);
        assertThat(tile.interpolate(50.5, 8.0)).isCloseTo(0.0, within(1e-6));
        assertThat(tile.interpolate(50.5, 8.5)).isCloseTo(500.0, within(1e-6));
        assertThat(tile.interpolate(50.5, 9.0)).isCloseTo(1000.0, within(1e-6));
    }

    @Test
    void aSourceWithNoDataAtAllProducesNoFile() throws IOException {
        writeHgt("N50E008.hgt", 5, (row, column) -> HgtRaster.VOID);

        DemTranscoder.Result result = transcoder(false, null).run(SourceRasters.find(input()));

        assertThat(result.tilesWritten()).isZero();
        assertThat(result.tilesEmpty()).isEqualTo(1);
        assertThat(output().resolve("N50E008.tfdem")).doesNotExist();
    }

    @Test
    void existingTilesAreSkippedUnlessOverwriteIsAskedFor() throws IOException {
        writeHgt("N50E008.hgt", 5, (row, column) -> (short) 100);
        transcoder(false, null).run(SourceRasters.find(input()));

        DemTranscoder.Result second = transcoder(false, null).run(SourceRasters.find(input()));
        assertThat(second.tilesSkipped()).isEqualTo(1);
        assertThat(second.tilesWritten()).isZero();

        DemTranscoder.Result third = transcoder(true, null).run(SourceRasters.find(input()));
        assertThat(third.tilesWritten()).isEqualTo(1);
    }

    @Test
    void theTileGridCanBeCoarsenedOnPurpose() throws IOException {
        writeHgt("N50E008.hgt", 101, (row, column) -> (short) 250);

        transcoder(false, 21).run(SourceRasters.find(input()));

        MappedDemTile tile = MappedDemTile.map(output().resolve("N50E008.tfdem"));
        assertThat(tile.width()).isEqualTo(21);
        assertThat(tile.sample(10, 10)).isCloseTo(250.0, within(1e-6));
    }

    @Test
    void severalSourcesFillEachOthersVoids() throws IOException {
        // Two sources over the same cell: the first is void in its eastern half.
        writeHgt("N50E008.hgt", 5, (row, column) -> column < 3 ? (short) 400 : HgtRaster.VOID);
        Path second = Files.createDirectories(scratch.resolve("in/more")).resolve("N50E008.hgt");
        ByteBuffer buffer = ByteBuffer.allocate(5 * 5 * 2).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < 25; i++) {
            buffer.putShort((short) 900);
        }
        Files.write(second, buffer.array());

        transcoder(false, 5).run(SourceRasters.find(input()));

        MappedDemTile tile = MappedDemTile.map(output().resolve("N50E008.tfdem"));
        assertThat(tile.sample(0, 0)).isCloseTo(400.0, within(1e-6));
        assertThat(tile.sample(4, 0)).isCloseTo(900.0, within(1e-6));
    }

    @Test
    void aSourceSpanningSeveralCellsProducesOneTilePerCell() {
        GeoBounds bounds = new GeoBounds(49.5, 7.5, 51.5, 9.5);

        assertThat(DemTranscoder.tilesCovered(bounds)).containsExactlyInAnyOrder(
                new DemTileKey(49, 7), new DemTileKey(49, 8), new DemTileKey(49, 9),
                new DemTileKey(50, 7), new DemTileKey(50, 8), new DemTileKey(50, 9),
                new DemTileKey(51, 7), new DemTileKey(51, 8), new DemTileKey(51, 9));
    }

    @Test
    void anExactDegreeCellCoversOnlyItself() {
        assertThat(DemTranscoder.tilesCovered(new GeoBounds(50, 8, 51, 9)))
                .containsExactly(new DemTileKey(50, 8));
    }

    @Test
    void anUnreadableSourceIsSkippedRatherThanAbandoningTheRun() throws IOException {
        writeHgt("N50E008.hgt", 5, (row, column) -> (short) 100);
        Files.write(input().resolve("N51E008.hgt"), new byte[7]); // odd byte count: not 16-bit HGT

        DemTranscoder.Result result = transcoder(false, null).run(SourceRasters.find(input()));

        assertThat(result.tilesWritten()).isEqualTo(1);
        assertThat(result.ok()).isTrue();
        assertThat(log).anyMatch(line -> line.contains("N51E008.hgt SKIPPED"));
    }
}
