package dev.terraforge.geo.dem;

import dev.terraforge.core.coord.GeoBounds;

/**
 * Identifies one DEM tile on the fixed 1x1 degree grid.
 *
 * <p>The grid is deliberately the same as SRTM/Copernicus tiling, so a source tile maps to exactly
 * one TerraForge tile and preparation stays a straight transcode.
 *
 * @param latDegree southern edge of the tile, integer degrees
 * @param lonDegree western edge of the tile, integer degrees
 */
public record DemTileKey(int latDegree, int lonDegree) {

    /** Southern edge of the northernmost row, and western edge of the easternmost column. */
    static final int MAX_LAT_DEGREE = 89;
    static final int MIN_LAT_DEGREE = -90;
    static final int MAX_LON_DEGREE = 179;
    static final int MIN_LON_DEGREE = -180;

    /**
     * The tile containing a point. The north pole and the antimeridian are the closing edges of the
     * grid, not the start of a new row or column: {@code floor(90)} is 90, and a tile {@code N90}
     * cannot exist, so a world bordered at the pole asked for hundreds of them and logged each one
     * as missing data. They belong to the last row and column, whose tiles reach those edges.
     */
    public static DemTileKey of(double latitude, double longitude) {
        return new DemTileKey(clampLatDegree((int) Math.floor(latitude)),
                clampLonDegree((int) Math.floor(longitude)));
    }

    /** A tile row clamped into the grid, see {@link #of}. */
    static int clampLatDegree(int latDegree) {
        return Math.clamp(latDegree, MIN_LAT_DEGREE, MAX_LAT_DEGREE);
    }

    /** A tile column clamped into the grid, see {@link #of}. */
    static int clampLonDegree(int lonDegree) {
        return Math.clamp(lonDegree, MIN_LON_DEGREE, MAX_LON_DEGREE);
    }

    public GeoBounds bounds() {
        return new GeoBounds(latDegree, lonDegree, latDegree + 1.0, lonDegree + 1.0);
    }

    /**
     * Reverses {@link #fileName()}: accepts {@code N50E008} with or without the extension.
     *
     * @throws IllegalArgumentException when the name does not follow the convention
     */
    public static DemTileKey parse(String name) {
        String stem = name.endsWith(".tfdem") ? name.substring(0, name.length() - ".tfdem".length()) : name;
        var matcher = NAME_PATTERN.matcher(stem);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a DEM tile name: " + name);
        }
        int latitude = Integer.parseInt(matcher.group(2));
        int longitude = Integer.parseInt(matcher.group(4));
        return new DemTileKey(
                matcher.group(1).equalsIgnoreCase("S") ? -latitude : latitude,
                matcher.group(3).equalsIgnoreCase("W") ? -longitude : longitude);
    }

    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("([NnSs])(\\d{2})([EeWw])(\\d{3})");

    /** File name convention: {@code N50E008.tfdem}, matching the SRTM naming scheme. */
    public String fileName() {
        return String.format(java.util.Locale.ROOT, "%s%02d%s%03d.tfdem",
                latDegree < 0 ? "S" : "N", Math.abs(latDegree),
                lonDegree < 0 ? "W" : "E", Math.abs(lonDegree));
    }

    @Override
    public String toString() {
        return fileName().replace(".tfdem", "");
    }
}
