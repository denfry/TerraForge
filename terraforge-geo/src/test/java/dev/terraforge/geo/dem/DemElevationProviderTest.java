package dev.terraforge.geo.dem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.terraforge.core.data.ElevationProvider;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The provider is what the terrain pipeline asks for elevation, so the important behaviours are the
 * boring ones: real values inside coverage, {@code NO_DATA} outside it, and never a guess.
 */
class DemElevationProviderTest {

    @RegisterExtension
    final ScratchDirectory scratch = new ScratchDirectory();

    /** Writes a tile whose elevation rises linearly from west to east, 0 m to 1000 m. */
    private void writeRamp(DemTileKey key) throws IOException {
        int side = 11;
        try (TfDemWriter writer = new TfDemWriter(scratch.path(), TfDemHeader.int16(key, side, side))) {
            for (int y = 0; y < side; y++) {
                double[] row = new double[side];
                for (int x = 0; x < side; x++) {
                    row[x] = x * 100.0;
                }
                writer.writeRow(row);
            }
        }
    }

    private DemElevationProvider provider() throws IOException {
        FileDemReader reader = new FileDemReader(scratch.path());
        DemCache cache = new DemCache(reader, 16, key -> { });
        return DemElevationProvider.of(cache, reader, 30.0, false);
    }

    @Test
    void elevationInsideCoverageFollowsTheData() throws IOException {
        writeRamp(new DemTileKey(50, 8));

        DemElevationProvider provider = provider();

        assertThat(provider.elevationAt(50.5, 8.0)).isCloseTo(0.0, within(1e-6));
        assertThat(provider.elevationAt(50.5, 8.5)).isCloseTo(500.0, within(1e-6));
        assertThat(provider.elevationAt(50.5, 9.0)).isCloseTo(1000.0, within(1e-6));
    }

    @Test
    void outsideCoverageIsNoDataRatherThanZero() throws IOException {
        writeRamp(new DemTileKey(50, 8));

        double elevation = provider().elevationAt(20.0, 100.0);

        // Zero would be a plausible-looking lie: the pipeline must be free to apply its own fallback.
        assertThat(ElevationProvider.isNoData(elevation)).isTrue();
    }

    /** A point on a shared edge must resolve, whichever side of it the prepared tile is on. */
    @Test
    void aPointOnATileEdgeFallsBackToTheNeighbour() throws IOException {
        writeRamp(new DemTileKey(50, 7));

        // Longitude 8.0 belongs to cell E008, which was never prepared; E007's east edge covers it.
        double elevation = provider().elevationAt(50.5, 8.0);

        assertThat(elevation).isCloseTo(1000.0, within(1e-6));
    }

    @Test
    void coverageAndMetadataAreReported() throws IOException {
        writeRamp(new DemTileKey(50, 8));

        DemElevationProvider provider = provider();

        assertThat(provider.name()).isEqualTo("tfdem");
        assertThat(provider.resolutionMeters()).isEqualTo(30.0);
        assertThat(provider.hasBathymetry()).isFalse();
        assertThat(provider.covers(50.5, 8.5)).isTrue();
        assertThat(provider.covers(10.0, 10.0)).isFalse();
    }

    @Test
    void aVoidInsideAPreparedTileStaysNoData() throws IOException {
        DemTileKey key = new DemTileKey(50, 8);
        try (TfDemWriter writer = new TfDemWriter(scratch.path(), TfDemHeader.int16(key, 3, 3))) {
            writer.writeRow(new double[]{Double.NaN, Double.NaN, Double.NaN});
            writer.writeRow(new double[]{Double.NaN, Double.NaN, Double.NaN});
            writer.writeRow(new double[]{Double.NaN, Double.NaN, Double.NaN});
        }

        assertThat(ElevationProvider.isNoData(provider().elevationAt(50.5, 8.5))).isTrue();
    }
}
