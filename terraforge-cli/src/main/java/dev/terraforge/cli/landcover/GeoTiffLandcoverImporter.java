package dev.terraforge.cli.landcover;

import dev.terraforge.cli.dem.GeoTiffMetadata;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.core.data.WorldCover;
import dev.terraforge.geo.dem.DemTileKey;
import dev.terraforge.geo.landcover.LandcoverGridFile;
import java.awt.Rectangle;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Imports an ESA WorldCover GeoTIFF, sliced into the one-degree grids the runtime loads.
 *
 * <p>WorldCover ships three-degree tiles of ten-metre pixels -- 36000 by 36000 samples, around
 * 90 MiB compressed. The runtime holds every prepared grid in memory, so importing that resolution
 * verbatim would cost gigabytes of server heap to describe terrain that one block already summarises
 * over a kilometre. The raster is therefore read <em>subsampled</em>: ImageIO is asked for every
 * n-th pixel, so the full-resolution grid is never allocated.
 *
 * <p>Subsampling is nearest-neighbour, and deliberately so. A land-cover class is a label, not a
 * measurement: averaging cropland and water yields neither. The sampled pixel is a real observation
 * of a real place, which is the same rule the DEM path follows.
 *
 * <p>The step must divide the source resolution exactly, so output cells line up with source pixels
 * and every one-degree grid has identical geometry regardless of which tile it came from.
 *
 * <p>The whole raster is read in one subsampled pass and then sliced in memory, rather than read a
 * cell at a time. That is not an optimisation: {@code ImageReadParam.setSourceRegion} is broken for
 * the tiled TIFFs WorldCover ships -- it throws {@code RasterFormatException} out of the decoder --
 * while a full subsampled read is both correct and quick. Subsampling is what keeps this affordable:
 * a 36000-square tile becomes a grid of a few megabytes before anything is retained.
 */
public final class GeoTiffLandcoverImporter {

    /**
     * Default output resolution, about 185 m per cell.
     *
     * <p>Chosen against the horizontal scale, not the source: at the default 1 block per kilometre a
     * finer grid cannot influence a single block, and 601 rows would cost five times the heap for a
     * distinction the world cannot show. Raise it for a zoomed-in world.
     */
    public static final int DEFAULT_SAMPLES_PER_DEGREE = 600;

    /** Ceiling on one subsampled read, matching the runtime's own limit on a prepared grid. */
    private static final long MAX_SUBSAMPLED_SAMPLES = 64L * 1024 * 1024;

    private GeoTiffLandcoverImporter() {
    }

    /** One prepared grid, or the reason a degree cell produced none. */
    public record Result(List<Path> written, List<String> skipped) {
    }

    /**
     * Slices {@code input} into one {@code .tflc} per degree cell it fully covers.
     *
     * @param outputDirectory directory the grids are written to, one file per degree cell
     * @param samplesPerDegree output cells per degree; must divide the source resolution
     * @param clip            only cells intersecting this box are written; {@code null} means all
     * @param replace         rewrite grids that already exist
     */
    public static Result importFile(Path input, Path outputDirectory, int samplesPerDegree,
                                    GeoBounds clip, boolean replace) throws IOException {
        GeoTiffMetadata metadata = GeoTiffMetadata.read(input);
        int step = step(metadata, samplesPerDegree);
        int pixelsPerDegree = step * samplesPerDegree;

        List<Path> written = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        ImageInputStream stream = ImageIO.createImageInputStream(input.toFile());
        if (stream == null) {
            throw new IOException("Cannot open " + input.getFileName() + " for reading");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) {
            stream.close();
            throw new IOException("No TIFF decoder is available for " + input.getFileName());
        }
        ImageReader reader = readers.next();
        Raster raster;
        try {
            reader.setInput(stream, true, true);
            List<DemTileKey> wanted = new ArrayList<>();
            for (DemTileKey cell : cells(metadata)) {
                if (clip != null && !intersects(cell, clip)) {
                    continue;
                }
                if (Files.exists(outputDirectory.resolve(cell + ".tflc")) && !replace) {
                    skipped.add(cell + " already prepared");
                    continue;
                }
                if (window(metadata, cell, pixelsPerDegree) == null) {
                    skipped.add(cell + " is only partly covered by " + input.getFileName());
                    continue;
                }
                wanted.add(cell);
            }
            if (wanted.isEmpty()) {
                return new Result(List.of(), List.copyOf(skipped));
            }
            raster = readSubsampled(reader, metadata, step);
        } finally {
            reader.dispose();
            stream.close();
        }

        for (DemTileKey cell : cells(metadata)) {
            Rectangle window = window(metadata, cell, pixelsPerDegree);
            if (window == null || (clip != null && !intersects(cell, clip))) {
                continue;
            }
            Path target = outputDirectory.resolve(cell + ".tflc");
            if (Files.exists(target) && !replace) {
                continue;
            }
            writeCell(raster, window.x / step, window.y / step, samplesPerDegree, cell, target);
            written.add(target);
        }
        return new Result(List.copyOf(written), List.copyOf(skipped));
    }

    /** One subsampled pass over the whole raster; nothing full-resolution is ever held. */
    private static Raster readSubsampled(ImageReader reader, GeoTiffMetadata metadata, int step)
            throws IOException {
        long samples = (long) Math.ceilDiv(metadata.width(), step) * Math.ceilDiv(metadata.height(), step);
        if (samples > MAX_SUBSAMPLED_SAMPLES) {
            throw new IOException("Reading this raster at the requested resolution needs "
                    + samples + " samples; lower --samples-per-degree");
        }
        ImageReadParam parameters = reader.getDefaultReadParam();
        parameters.setSourceSubsampling(step, step, 0, 0);
        return reader.readRaster(0, parameters);
    }

    /** Copies one degree cell out of the subsampled raster and writes the prepared grid. */
    private static void writeCell(Raster raster, int originX, int originY, int samples,
                                  DemTileKey cell, Path target) throws IOException {
        if (originX + samples > raster.getWidth() || originY + samples > raster.getHeight()) {
            throw new IOException("The decoder returned " + raster.getWidth() + "x" + raster.getHeight()
                    + " samples, too few for " + cell);
        }
        LandcoverClass[] values = new LandcoverClass[samples * samples];
        for (int y = 0; y < samples; y++) {
            int row = y * samples;
            for (int x = 0; x < samples; x++) {
                values[row + x] = WorldCover.fromCode(raster.getSample(
                        raster.getMinX() + originX + x, raster.getMinY() + originY + y, 0));
            }
        }
        LandcoverGridFile.write(target, cell.latDegree(), cell.lonDegree(),
                cell.latDegree() + 1.0, cell.lonDegree() + 1.0, samples, samples, values);
    }

    /**
     * Source pixels per output cell.
     *
     * @throws IOException when the source resolution is not a whole multiple of the requested one
     */
    private static int step(GeoTiffMetadata metadata, int samplesPerDegree) throws IOException {
        if (samplesPerDegree < 1) {
            throw new IOException("samples-per-degree must be at least 1");
        }
        int pixelsLat = pixelsPerDegree(metadata.pixelSizeLat());
        int pixelsLon = pixelsPerDegree(metadata.pixelSizeLon());
        if (pixelsLat != pixelsLon) {
            throw new IOException("The raster has different resolutions per axis ("
                    + pixelsLon + " x " + pixelsLat + " pixels per degree); "
                    + "resample it square with 'gdalwarp -tr <deg> <deg>'");
        }
        if (pixelsLat % samplesPerDegree != 0) {
            throw new IOException("The raster has " + pixelsLat + " pixels per degree, which is not a "
                    + "whole multiple of the requested " + samplesPerDegree + " samples per degree; "
                    + "pick a divisor of " + pixelsLat);
        }
        return pixelsLat / samplesPerDegree;
    }

    private static int pixelsPerDegree(double pixelSize) throws IOException {
        double exact = 1.0 / pixelSize;
        long rounded = Math.round(exact);
        // A whole number of pixels per degree is what makes a degree cell an exact pixel window.
        // WorldCover, and every dataset published on a degree grid, satisfies this.
        if (rounded < 1 || Math.abs(exact - rounded) > 1e-6 * rounded) {
            throw new IOException("The raster does not have a whole number of pixels per degree ("
                    + String.format(Locale.ROOT, "%.6f", exact) + "); "
                    + "align it to a degree grid with 'gdalwarp -tap -tr <deg> <deg>'");
        }
        return (int) rounded;
    }

    /** Every degree cell the raster touches, north-west to south-east. */
    private static List<DemTileKey> cells(GeoTiffMetadata metadata) {
        List<DemTileKey> keys = new ArrayList<>();
        int minLat = (int) Math.floor(metadata.southLatitude());
        int maxLat = (int) Math.ceil(metadata.originLat()) - 1;
        int minLon = (int) Math.floor(metadata.originLon());
        int maxLon = (int) Math.ceil(metadata.eastLongitude()) - 1;
        for (int lat = maxLat; lat >= minLat; lat--) {
            for (int lon = minLon; lon <= maxLon; lon++) {
                if (lat >= -90 && lat < 90 && lon >= -180 && lon < 180) {
                    keys.add(new DemTileKey(lat, lon));
                }
            }
        }
        return keys;
    }

    /**
     * Pixel window of one degree cell, or {@code null} when the raster does not cover all of it.
     *
     * <p>A partly covered cell is skipped rather than padded: a grid half filled with
     * {@code UNKNOWN} would shadow the neighbouring tile that does have the data, because the
     * runtime takes the first grid that answers.
     */
    private static Rectangle window(GeoTiffMetadata metadata, DemTileKey cell, int pixelsPerDegree) {
        long x = Math.round((cell.lonDegree() - metadata.originLon()) / metadata.pixelSizeLon());
        long y = Math.round((metadata.originLat() - (cell.latDegree() + 1.0)) / metadata.pixelSizeLat());
        if (x < 0 || y < 0
                || x + pixelsPerDegree > metadata.width() || y + pixelsPerDegree > metadata.height()) {
            return null;
        }
        return new Rectangle((int) x, (int) y, pixelsPerDegree, pixelsPerDegree);
    }

    private static boolean intersects(DemTileKey cell, GeoBounds clip) {
        return cell.lonDegree() < clip.maxLongitude() && cell.lonDegree() + 1.0 > clip.minLongitude()
                && cell.latDegree() < clip.maxLatitude() && cell.latDegree() + 1.0 > clip.minLatitude();
    }
}
