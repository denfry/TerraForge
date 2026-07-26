package dev.terraforge.cli.dem;

import dev.terraforge.core.coord.GeoBounds;
import java.io.Closeable;
import java.io.IOException;

/**
 * A geo-referenced elevation raster read from a source file, before transcoding.
 *
 * <p>Only the CLI has these. The server never opens a GeoTIFF or an HGT -- it reads {@code .tfdem}
 * tiles produced from them, which is what keeps a GIS stack out of the plugin jar.
 */
public interface SourceRaster extends Closeable {

    /** Area covered, from the raster's own geo-referencing. */
    GeoBounds bounds();

    int width();

    int height();

    /** Ground resolution in metres at the raster's centre latitude, for reporting. */
    double resolutionMeters();

    /** Finer of the two pixel steps in degrees; sets the natural sample count of a prepared tile. */
    double degreesPerPixel();

    /**
     * Elevation in metres at an exact point, bilinearly interpolated between pixel centres.
     *
     * @return metres, or {@code NaN} outside the raster and at no-data pixels
     */
    double elevationAt(double latitude, double longitude);

    /** True when the source stores sub-metre or below-sea-floor values worth keeping as float32. */
    boolean needsFloatPrecision();

    /** One line for the preparation log. */
    String describe();

    @Override
    void close() throws IOException;
}
