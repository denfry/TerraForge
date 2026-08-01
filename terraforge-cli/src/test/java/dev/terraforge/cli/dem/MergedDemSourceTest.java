package dev.terraforge.cli.dem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.geo.dem.DemTileKey;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class MergedDemSourceTest {

    @Test
    void keepsPositiveLandAndFillsLandVoidsAndSeaWithNearestBathymetry() throws IOException {
        DemTileKey key = new DemTileKey(43, 6);
        DemSource land = source(key, new double[][] {
                {12.0, 0.0, ElevationProvider.NO_DATA},
                {8.0, -1.0, ElevationProvider.NO_DATA},
        });
        DemSource bathymetry = source(key, new double[][] {
                {-10.0, -20.0},
                {-30.0, -40.0},
        });

        MergedDemSource merged = new MergedDemSource(land, bathymetry);

        assertThat(merged.readRow(0)).containsExactly(12.0, -20.0, -20.0);
        assertThat(merged.readRow(1)).containsExactly(8.0, -40.0, -40.0);
        assertThat(merged.containsBathymetry()).isTrue();
    }

    private static DemSource source(DemTileKey key, double[][] rows) {
        return new DemSource() {
            @Override public DemTileKey key() { return key; }
            @Override public int width() { return rows[0].length; }
            @Override public int height() { return rows.length; }
            @Override public double[] readRow(int y) { return rows[y]; }
            @Override public void close() { }
        };
    }
}
