package dev.terraforge.geo.dem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.coord.GeoBounds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class FileDemReaderTest {

    @RegisterExtension
    final ScratchDirectory scratch = new ScratchDirectory();

    private void writeTile(DemTileKey key, double elevation) throws IOException {
        try (TfDemWriter writer = new TfDemWriter(scratch.path(), TfDemHeader.int16(key, 2, 2))) {
            writer.writeRow(new double[]{elevation, elevation});
            writer.writeRow(new double[]{elevation, elevation});
        }
    }

    @Test
    void tilesInTheDirectoryAreDiscoveredAndReadable() throws IOException {
        writeTile(new DemTileKey(50, 8), 300);
        writeTile(new DemTileKey(51, 8), 120);

        FileDemReader reader = new FileDemReader(scratch.path());

        assertThat(reader.tileCount()).isEqualTo(2);
        assertThat(reader.exists(new DemTileKey(50, 8))).isTrue();
        assertThat(reader.exists(new DemTileKey(60, 8))).isFalse();
        assertThat(reader.read(new DemTileKey(50, 8)))
                .get()
                .extracting(tile -> tile.sample(0, 0))
                .isEqualTo(300.0);
        assertThat(reader.read(new DemTileKey(60, 8))).isEmpty();
    }

    @Test
    void aMissingDirectoryIsEmptyRatherThanAnError() throws IOException {
        FileDemReader reader = new FileDemReader(scratch.resolve("not-created-yet"));

        assertThat(reader.tileCount()).isZero();
        assertThat(reader.availableTiles()).isEmpty();
    }

    /** Anything else in the data directory is the operator's business, not a startup failure. */
    @Test
    void unrelatedFilesAreIgnored() throws IOException {
        writeTile(new DemTileKey(50, 8), 10);
        Files.writeString(scratch.resolve("README.txt"), "notes");
        Files.writeString(scratch.resolve("garbage.tfdem"), "not a tile");

        FileDemReader reader = new FileDemReader(scratch.path());

        assertThat(reader.availableTiles()).containsExactly(new DemTileKey(50, 8));
    }

    @Test
    void tileNamesParseInBothHemispheres() {
        assertThat(FileDemReader.parseKey("N50E008.tfdem")).contains(new DemTileKey(50, 8));
        assertThat(FileDemReader.parseKey("S34W059.tfdem")).contains(new DemTileKey(-34, -59));
        assertThat(FileDemReader.parseKey("N00E000.tfdem")).contains(new DemTileKey(0, 0));
        assertThat(FileDemReader.parseKey("X50E008.tfdem")).isEmpty();
        assertThat(FileDemReader.parseKey("N50E8.tfdem")).isEmpty();
    }

    @Test
    void coverageIsTheUnionOfThePreparedTiles() throws IOException {
        writeTile(new DemTileKey(50, 8), 1);
        writeTile(new DemTileKey(51, 9), 1);

        GeoBounds coverage = DemElevationProvider.coverageOf(new FileDemReader(scratch.path()));

        assertThat(coverage).isEqualTo(new GeoBounds(50, 8, 52, 10));
    }

    @Test
    void coverageWithoutTilesIsEmptyRatherThanTheWholePlanet() throws IOException {
        GeoBounds coverage = DemElevationProvider.coverageOf(new FileDemReader(scratch.path()));

        assertThat(coverage.latitudeSpan()).isZero();
        assertThat(coverage.longitudeSpan()).isZero();
    }

    @Test
    void aTileFileNameRoundTripsThroughTheKey() {
        Path file = Path.of(new DemTileKey(-1, -1).fileName());

        assertThat(file.toString()).isEqualTo("S01W001.tfdem");
        assertThat(FileDemReader.parseKey(file.toString())).contains(new DemTileKey(-1, -1));
    }
}
