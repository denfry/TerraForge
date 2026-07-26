package dev.terraforge.cli.dem;

import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.geodesy.Geodesy;

/**
 * A {@link SourceRaster} on a regular latitude/longitude grid.
 *
 * <p>Both supported sources -- SRTM HGT and geographic GeoTIFF -- are affine grids in WGS84, so the
 * geometry lives here once and subclasses only supply raw pixels. Anything that is not an affine
 * WGS84 grid is rejected at load time rather than silently resampled wrong; reprojection is a job
 * for GDAL, not for TerraForge.
 */
abstract class GridRaster implements SourceRaster {

    /** Geographic position of the centre of pixel (0, 0), and the step between pixel centres. */
    private final double centerLon0;
    private final double centerLat0;
    private final double lonStep;
    private final double latStep; // negative: row index grows southward
    private final int width;
    private final int height;

    GridRaster(double centerLat0, double centerLon0, double latStep, double lonStep, int width, int height) {
        if (latStep >= 0) {
            throw new IllegalArgumentException("north-up rasters only: latitude step must be negative");
        }
        if (lonStep <= 0) {
            throw new IllegalArgumentException("west-to-east rasters only: longitude step must be positive");
        }
        this.centerLat0 = centerLat0;
        this.centerLon0 = centerLon0;
        this.latStep = latStep;
        this.lonStep = lonStep;
        this.width = width;
        this.height = height;
    }

    /** Raw pixel in metres, or {@code NaN} for no-data and out-of-range indices. */
    protected abstract double rawSample(int x, int y);

    @Override
    public final int width() {
        return width;
    }

    @Override
    public final int height() {
        return height;
    }

    /**
     * The area spanned by the outermost pixel <em>centres</em>.
     *
     * <p>Deliberately not the half-pixel-larger area a pixel-is-area raster nominally covers: those
     * outer half pixels cannot be interpolated, only extrapolated. Reporting them as coverage would
     * make an HGT tile that lines up exactly with a degree cell look as if it spilled into all eight
     * neighbours, and would have the transcoder write eight tiles of almost nothing.
     */
    @Override
    public final GeoBounds bounds() {
        double north = centerLat0;
        double south = centerLat0 + latStep * (height - 1);
        double west = centerLon0;
        double east = centerLon0 + lonStep * (width - 1);
        return new GeoBounds(south, west, north, east);
    }

    @Override
    public final double degreesPerPixel() {
        return Math.min(Math.abs(latStep), lonStep);
    }

    @Override
    public final double resolutionMeters() {
        double centerLatitude = bounds().center().latitude();
        double northSouth = Math.abs(latStep) * Geodesy.MEAN_RADIUS * Math.PI / 180.0;
        double eastWest = lonStep * Geodesy.MEAN_RADIUS * Math.PI / 180.0
                * Math.cos(Math.toRadians(centerLatitude));
        return Math.min(northSouth, eastWest);
    }

    @Override
    public final double elevationAt(double latitude, double longitude) {
        double gx = (longitude - centerLon0) / lonStep;
        double gy = (latitude - centerLat0) / latStep;
        // Outside the outermost pixel centres by more than half a pixel there is genuinely no data;
        // within half a pixel, clamp so the raster's own edge pixels still cover its stated bounds.
        if (gx < -0.5 || gy < -0.5 || gx > width - 0.5 || gy > height - 0.5) {
            return Double.NaN;
        }
        gx = Math.min(Math.max(gx, 0.0), width - 1.0);
        gy = Math.min(Math.max(gy, 0.0), height - 1.0);

        int x0 = Math.min((int) Math.floor(gx), Math.max(width - 2, 0));
        int y0 = Math.min((int) Math.floor(gy), Math.max(height - 2, 0));
        double fx = gx - x0;
        double fy = gy - y0;

        double sum = 0.0;
        double weight = 0.0;
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = 0; dx <= 1; dx++) {
                double value = rawSample(x0 + dx, y0 + dy);
                if (Double.isNaN(value)) {
                    continue; // a void neighbour is excluded, not propagated
                }
                double w = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy);
                sum += value * w;
                weight += w;
            }
        }
        return weight == 0.0 ? Double.NaN : sum / weight;
    }
}
