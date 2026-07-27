package dev.terraforge.geo.landcover;

import dev.terraforge.core.data.LandcoverProvider;
import java.util.Objects;

/** Read-only categorical land-cover grid in WGS84; samples use nearest-neighbour lookup. */
public final class GridLandcoverProvider implements LandcoverProvider {
    private final double south;
    private final double west;
    private final double north;
    private final double east;
    private final int width;
    private final int height;
    private final LandcoverClass[] values;

    public GridLandcoverProvider(double south, double west, double north, double east,
                                 int width, int height, LandcoverClass[] values) {
        if (!(south < north && west < east) || width < 1 || height < 1 || values.length != width * height) {
            throw new IllegalArgumentException("Invalid land-cover grid");
        }
        this.south = south; this.west = west; this.north = north; this.east = east;
        this.width = width; this.height = height; this.values = values.clone();
        for (LandcoverClass value : this.values) Objects.requireNonNull(value, "landcover value");
    }

    @Override
    public LandcoverClass landcoverAt(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < south || latitude > north || longitude < west || longitude > east) {
            return LandcoverClass.UNKNOWN;
        }
        int x = Math.min(width - 1, (int) ((longitude - west) / (east - west) * width));
        int y = Math.min(height - 1, (int) ((north - latitude) / (north - south) * height));
        return values[y * width + x];
    }
}
