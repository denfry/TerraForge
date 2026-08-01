package dev.terraforge.geo.landcover;

import dev.terraforge.core.data.LandcoverProvider;
import java.util.Objects;

/**
 * Read-only categorical land-cover grid in WGS84; samples use nearest-neighbour lookup.
 *
 * <p>Classes are held as their ordinals in a {@code byte[]}, not as an enum array. The distinction
 * decides whether a planet-wide world is possible at all: a reference array costs four bytes per
 * cell, so the ~21,000 prepared degree cells of a whole-Earth import would need tens of gigabytes of
 * heap to hold what is, in fact, one byte of information per cell.
 */
public final class GridLandcoverProvider implements LandcoverProvider {

    private static final LandcoverClass[] CLASSES = LandcoverClass.values();

    private final double south;
    private final double west;
    private final double north;
    private final double east;
    private final int width;
    private final int height;
    private final byte[] ordinals;

    public GridLandcoverProvider(double south, double west, double north, double east,
                                 int width, int height, LandcoverClass[] values) {
        this(south, west, north, east, width, height, toOrdinals(values, width, height));
    }

    /**
     * @param ordinals one {@link LandcoverClass} ordinal per cell, row-major from the north-west
     *                 corner; taken as given, not copied, so callers must not retain it
     */
    public GridLandcoverProvider(double south, double west, double north, double east,
                                 int width, int height, byte[] ordinals) {
        if (!(south < north && west < east) || width < 1 || height < 1
                || ordinals.length != (long) width * height) {
            throw new IllegalArgumentException("Invalid land-cover grid");
        }
        for (byte ordinal : ordinals) {
            if (ordinal < 0 || ordinal >= CLASSES.length) {
                throw new IllegalArgumentException("Unknown land-cover class ordinal " + ordinal);
            }
        }
        this.south = south; this.west = west; this.north = north; this.east = east;
        this.width = width; this.height = height; this.ordinals = ordinals;
    }

    private static byte[] toOrdinals(LandcoverClass[] values, int width, int height) {
        if (width < 1 || height < 1 || values.length != (long) width * height) {
            throw new IllegalArgumentException("Invalid land-cover grid");
        }
        byte[] ordinals = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            ordinals[i] = (byte) Objects.requireNonNull(values[i], "landcover value").ordinal();
        }
        return ordinals;
    }

    /** Geographic extent, as south, west, north, east. */
    public double south() { return south; }

    public double west() { return west; }

    public double north() { return north; }

    public double east() { return east; }

    /** Retained bytes, used to weigh this grid in the runtime cache. */
    public long sizeBytes() {
        return ordinals.length + 64L;
    }

    @Override
    public LandcoverClass landcoverAt(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < south || latitude > north || longitude < west || longitude > east) {
            return LandcoverClass.UNKNOWN;
        }
        int x = Math.min(width - 1, (int) ((longitude - west) / (east - west) * width));
        int y = Math.min(height - 1, (int) ((north - latitude) / (north - south) * height));
        return CLASSES[ordinals[y * width + x]];
    }
}
