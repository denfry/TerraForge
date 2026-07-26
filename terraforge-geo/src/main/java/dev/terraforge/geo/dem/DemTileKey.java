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

    public static DemTileKey of(double latitude, double longitude) {
        return new DemTileKey((int) Math.floor(latitude), (int) Math.floor(longitude));
    }

    public GeoBounds bounds() {
        return new GeoBounds(latDegree, lonDegree, latDegree + 1.0, lonDegree + 1.0);
    }

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
