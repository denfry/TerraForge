package dev.terraforge.cli.dem;

import dev.terraforge.geo.dem.DemTileKey;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/** Uses land elevation where positive, otherwise fills the source cell from nearest GEBCO data. */
public final class MergedDemSource implements DemSource {

    private final DemSource land;
    private final DemSource bathymetry;

    public MergedDemSource(DemSource land, DemSource bathymetry) {
        this.land = Objects.requireNonNull(land, "land");
        this.bathymetry = Objects.requireNonNull(bathymetry, "bathymetry");
        if (!land.key().equals(bathymetry.key())) {
            throw new IllegalArgumentException("Land and bathymetry must cover the same tile");
        }
    }

    @Override public DemTileKey key() { return land.key(); }
    @Override public int width() { return land.width(); }
    @Override public int height() { return land.height(); }

    @Override
    public double[] readRow(int y) throws IOException {
        double[] landRow = land.readRow(y);
        double[] bathymetryRow = bathymetry.readRow(nearest(y, height(), bathymetry.height()));
        double[] result = new double[landRow.length];
        for (int x = 0; x < result.length; x++) {
            double elevation = landRow[x];
            int bathymetryX = nearest(x, result.length, bathymetryRow.length);
            result[x] = elevation > 0.0 ? elevation : bathymetryRow[bathymetryX];
        }
        return result;
    }

    @Override
    public boolean containsBathymetry() throws IOException {
        for (int y = 0; y < height(); y++) {
            if (Arrays.stream(land.readRow(y)).anyMatch(value -> !Double.isFinite(value) || value <= 0.0)) {
                return true;
            }
        }
        return false;
    }

    @Override public boolean needsFloat32() { return land.needsFloat32() || bathymetry.needsFloat32(); }

    private static int nearest(int coordinate, int targetSize, int sourceSize) {
        return (int) Math.round((double) coordinate * (sourceSize - 1) / (targetSize - 1));
    }
    @Override public void close() throws IOException { land.close(); bathymetry.close(); }
}
