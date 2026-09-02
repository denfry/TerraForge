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

    @Test
    void aFootprintAveragesTheSamplesItCoversInsteadOfPickingOne() throws IOException {
        // A 5x5 tile with one spike at the centre sample. Point sampling the spike reports it as the
        // whole block's height; averaging the block's footprint reports the ground it covers.
        writeRows(TfDemHeader.int16(new DemTileKey(50, 8), 5, 5), new double[][] {
                {0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0},
                {0, 0, 400, 0, 0},
                {0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0},
        });
        ElevationProvider provider = provider();

        assertThat(provider.elevationAt(50.5, 8.5)).isCloseTo(400.0, Offset.offset(1e-6));
        // The footprint covers the spike and the eight samples around it: 400/9.
        assertThat(provider.averageElevationAt(50.5, 8.5, 0.5, 0.5))
                .isCloseTo(400.0 / 9.0, Offset.offset(1e-6));
    }

    @Test
    void aFootprintFinerThanTheGridFallsBackToInterpolation() throws IOException {
        writeFlatTile(new DemTileKey(50, 8), 250.0);
        ElevationProvider provider = provider();

        // Nothing to average -- the box sits between samples. Reporting a hole here would punch one
        // in perfectly good prepared data at every fine blocks-per-km setting.
        assertThat(provider.averageElevationAt(50.5, 8.5, 1e-6, 1e-6))
                .isCloseTo(250.0, Offset.offset(1e-6));
    }

    @Test
    void aFootprintStraddlingATileBorderReadsBothTilesWithoutDoubleCountingTheSharedEdge() throws IOException {
        // 3x3 grids: samples at 0.0/0.5/1.0 degrees, the 1.0 column being the neighbour's 0.0 column.
        writeRows(TfDemHeader.int16(new DemTileKey(50, 8), 3, 3), flat(3, 100.0));
        writeRows(TfDemHeader.int16(new DemTileKey(50, 9), 3, 3), flat(3, 300.0));
        ElevationProvider provider = provider();

        // The box spans lon 8.5..9.4: one owned column from the west tile (8.5) and one from the
        // east tile (9.0, which is the shared meridian, owned by exactly one of them). Equal counts,
        // so the mean is the midpoint. A tile that clamped to its own edge would answer 100 or 300.
        assertThat(provider.averageElevationAt(50.5, 8.95, 1.0, 0.9))
                .isCloseTo(200.0, Offset.offset(1e-6));
    }

    @Test
    void anUncoveredFootprintIsStillNoData() throws IOException {
        writeFlatTile(new DemTileKey(50, 8), 250.0);
        ElevationProvider provider = provider();

        assertThat(ElevationProvider.isNoData(provider.averageElevationAt(10.5, 20.5, 0.5, 0.5))).isTrue();
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

    /** A tile with explicit per-sample values, north row first. */
    private void writeRows(TfDemHeader header, double[][] rows) throws IOException {
        try (TfDemWriter writer = TfDemWriter.create(demDirectory, header)) {
            for (double[] row : rows) {
                writer.writeRow(row);
            }
        }
    }

    private static double[][] flat(int size, double meters) {
        double[][] rows = new double[size][size];
        for (double[] row : rows) {
            java.util.Arrays.fill(row, meters);
        }
        return rows;
    }
}
