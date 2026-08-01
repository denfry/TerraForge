package dev.terraforge.geo.dem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.data.ElevationProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class DemElevationProviderTest {

    /** Mapped tiles stay locked on Windows until collected; see TfDemRoundTripTest. */
    @TempDir(cleanup = CleanupMode.NEVER)
    Path demDirectory;

    @Test
    void samplesAPreparedTile() throws IOException {
        writeFlatTile(new DemTileKey(50, 8), 250.0);
        ElevationProvider provider = provider();

        assertThat(provider.elevationAt(50.5, 8.5)).isCloseTo(250.0, Offset.offset(1e-6));
        assertThat(provider.hasCoverage(50.5, 8.5)).isTrue();
    }

    @Test
    void missingTilesYieldNoDataAndAreReportedOnce() throws IOException {
        writeFlatTile(new DemTileKey(50, 8), 250.0);
        DemElevationProvider provider = provider();

        assertThat(ElevationProvider.isNoData(provider.elevationAt(10.5, 20.5))).isTrue();
        assertThat(ElevationProvider.isNoData(provider.elevationAt(10.6, 20.6))).isTrue();
        assertThat(provider.hasCoverage(10.5, 20.5)).isFalse();
        assertThat(provider.missingTiles()).containsExactly(new DemTileKey(10, 20));
    }

    @Test
    void coverageIsTheUnionOfPreparedTiles() throws IOException {
        writeFlatTile(new DemTileKey(47, 5), 100.0);
        writeFlatTile(new DemTileKey(49, 7), 100.0);

        assertThat(provider().coverage())
                .isEqualTo(new dev.terraforge.core.coord.GeoBounds(47.0, 5.0, 50.0, 8.0));
    }

    @Test
    void explicitBathymetryFlagIsReportedIndependentlyOfEncoding() throws IOException {
        writeFlatTile(new DemTileKey(50, 8), 250.0);
        assertThat(provider().hasBathymetry()).isFalse();

        writeTile(TfDemHeader.int16(new DemTileKey(50, 9), 2, 2, true), -40.0);
        assertThat(provider().hasBathymetry()).isTrue();
    }

    @Test
    void versionOneFloat32TilesKeepTheLegacyBathymetryMeaning() throws IOException {
        DemTileKey key = new DemTileKey(50, 8);
        TfDemHeader legacy = new TfDemHeader(TfDemFormat.LEGACY_VERSION, TfDemFormat.ENCODING_FLOAT32,
                2, 2, key.latDegree(), key.lonDegree(), 0, 1, 1, false);
        writeTile(legacy, -40.0);

        assertThat(provider().hasBathymetry()).isTrue();
    }

    @Test
    void tilesAreCachedRatherThanRemappedPerQuery() throws IOException {
        writeFlatTile(new DemTileKey(50, 8), 250.0);
        DemElevationProvider provider = provider();

        for (int i = 0; i < 100; i++) {
            provider.elevationAt(50.5, 8.5);
        }

        var stats = provider.cacheStatistics();
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.hits()).isEqualTo(99);
        assertThat(stats.sizeBytes()).isPositive();
    }

    @Test
    void anEmptyDataDirectoryIsNotAnError() throws IOException {
        DemElevationProvider provider = provider();

        assertThat(ElevationProvider.isNoData(provider.elevationAt(50.5, 8.5))).isTrue();
        assertThat(provider.coverage().minLatitude()).isZero();
    }

    @Test
    void filesThatAreNotTilesAreSkipped() throws IOException {
        writeFlatTile(new DemTileKey(50, 8), 250.0);
        Files.writeString(demDirectory.resolve("N51E009.tfdem"), "definitely not a raster");
        Files.writeString(demDirectory.resolve("readme.txt"), "notes");

        DemElevationProvider provider = provider();

        assertThat(provider.hasCoverage(50.5, 8.5)).isTrue();
        assertThat(provider.hasCoverage(51.5, 9.5)).isFalse();
    }

    /** A fresh manager per provider: cache names are registered once, and tests build several. */
    private DemElevationProvider provider() throws IOException {
        return new DemElevationProvider(FileDemReader.open(demDirectory), new CacheManager(64), 16);
    }

    private void writeFlatTile(DemTileKey key, double meters) throws IOException {
        writeTile(TfDemHeader.int16(key, 2, 2), meters);
    }

    private void writeTile(TfDemHeader header, double meters) throws IOException {
        try (TfDemWriter writer = TfDemWriter.create(demDirectory, header)) {
            for (int y = 0; y < header.height(); y++) {
                double[] row = new double[header.width()];
                java.util.Arrays.fill(row, meters);
                writer.writeRow(row);
            }
        }
    }
}
