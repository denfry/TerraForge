package dev.terraforge.cli.dem;

import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.geo.dem.DemTileKey;
import java.awt.Rectangle;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * A georeferenced GeoTIFF elevation raster, sliced into the one-degree cells TerraForge stores.
 *
 * <p>A {@code .tfdem} tile is exactly one degree cell, while a GeoTIFF covers whatever its author
 * chose. This class is therefore the adapter between the two: it reports which cells the raster
 * covers and hands out a {@link DemSource} per cell.
 *
 * <p>Rows are pulled from ImageIO a strip at a time, so preparing a continental raster costs a few
 * hundred rows of memory, not the whole grid. The strip is what makes a tiled COG affordable: those
 * rasters are stored as square tiles, so asking for a single row still decodes every tile it crosses
 * -- reading one row at a time re-decodes the same tiles hundreds of times over, turning a tile that
 * should take seconds into many minutes. The reader is shared by every cell source and closed with
 * this file.
 *
 * <p>Resampling is nearest-neighbour on purpose. A DEM is measured data: interpolating it here
 * would invent elevations that no survey recorded, and the generator already interpolates between
 * samples when it needs a value between grid points.
 */
public final class GeoTiffDemFile implements AutoCloseable {

    /** Cap on the output grid so a very fine raster cannot produce an unreadably large tile. */
    private static final int MAX_SAMPLES_PER_AXIS = 3601;

    private static final int MIN_SAMPLES_PER_AXIS = 2;

    private final GeoTiffMetadata metadata;
    private final ImageInputStream stream;
    private final ImageReader reader;
    private final int samplesPerAxis;

    /**
     * Rows decoded per read. Large enough to cover a COG's tile height in one pass, small enough
     * that the strip stays a few megabytes however wide the raster is.
     */
    private static final int STRIP_ROWS = 512;

    private int stripStart = -1;
    private int stripRows;
    private double[] strip;

    private GeoTiffDemFile(GeoTiffMetadata metadata, ImageInputStream stream, ImageReader reader) {
        this.metadata = metadata;
        this.stream = stream;
        this.reader = reader;
        this.samplesPerAxis = samplesPerAxis(metadata);
    }

    public static GeoTiffDemFile open(Path file) throws IOException {
        GeoTiffMetadata metadata = GeoTiffMetadata.read(file);
        ImageInputStream stream = ImageIO.createImageInputStream(file.toFile());
        if (stream == null) {
            throw new IOException("Cannot open " + file.getFileName() + " for reading");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) {
            stream.close();
            throw new IOException("No TIFF decoder is available for " + file.getFileName());
        }
        ImageReader reader = readers.next();
        reader.setInput(stream, true, true);
        return new GeoTiffDemFile(metadata, stream, reader);
    }

    /**
     * Output samples per axis, derived from the source resolution.
     *
     * <p>One-arc-second data gives 3601 and three-arc-second data 1201, matching SRTM, so a tile
     * prepared from GeoTIFF is the same shape as one prepared from {@code .hgt}.
     */
    private static int samplesPerAxis(GeoTiffMetadata metadata) {
        double finest = Math.min(metadata.pixelSizeLat(), metadata.pixelSizeLon());
        long samples = Math.round(1.0 / finest) + 1;
        return (int) Math.max(MIN_SAMPLES_PER_AXIS, Math.min(MAX_SAMPLES_PER_AXIS, samples));
    }

    /** Every one-degree cell the raster touches, in a deterministic north-west to south-east order. */
    public List<DemTileKey> tiles() {
        int minLat = (int) Math.floor(metadata.southLatitude());
        int maxLat = (int) Math.ceil(metadata.originLat()) - 1;
        int minLon = (int) Math.floor(metadata.originLon());
        int maxLon = (int) Math.ceil(metadata.eastLongitude()) - 1;
        List<DemTileKey> keys = new ArrayList<>();
        for (int lat = maxLat; lat >= minLat; lat--) {
            for (int lon = minLon; lon <= maxLon; lon++) {
                if (lat >= -90 && lat < 90 && lon >= -180 && lon < 180) {
                    keys.add(new DemTileKey(lat, lon));
                }
            }
        }
        return keys;
    }

    /** A source covering one cell. Closing it does not close the underlying raster. */
    public DemSource sourceFor(DemTileKey key) {
        return new CellSource(key);
    }

    @Override
    public void close() throws IOException {
        reader.dispose();
        stream.close();
    }

    /**
     * Value at one source pixel, reading and caching the strip of rows it falls in.
     *
     * <p>Output rows walk the raster from north to south, so a strip serves every sample of every
     * output row that lands in it before the next one is read.
     */
    private double sourcePixel(int x, int y) throws IOException {
        if (stripStart < 0 || y < stripStart || y >= stripStart + stripRows) {
            readStrip(y);
        }
        return strip[(y - stripStart) * metadata.width() + x];
    }

    private void readStrip(int y) throws IOException {
        int start = y - y % STRIP_ROWS;
        int rows = Math.min(STRIP_ROWS, metadata.height() - start);
        int width = metadata.width();
        ImageReadParam parameters = reader.getDefaultReadParam();
        parameters.setSourceRegion(new Rectangle(0, start, width, rows));
        Raster raster = reader.readRaster(0, parameters);
        // Measured against Raster.getSamples: the bulk form is about twice as slow here, because
        // this reader's raster has no optimised override and the generic path costs more per pixel
        // than getSampleDouble does. The obvious optimisation is the wrong one.
        double[] values = strip != null && strip.length == width * rows ? strip : new double[width * rows];
        int minX = raster.getMinX();
        int minY = raster.getMinY();
        for (int row = 0; row < rows; row++) {
            int offset = row * width;
            int sourceY = minY + row;
            for (int column = 0; column < width; column++) {
                values[offset + column] = raster.getSampleDouble(minX + column, sourceY, 0);
            }
        }
        stripStart = start;
        stripRows = rows;
        strip = values;
    }

    private double sample(double latitude, double longitude) throws IOException {
        int x = (int) Math.floor((longitude - metadata.originLon()) / metadata.pixelSizeLon());
        int y = (int) Math.floor((metadata.originLat() - latitude) / metadata.pixelSizeLat());
        // A cell's south and east edges land exactly one pixel past the raster. Those edge samples
        // belong to the last pixel, not to a void: without this, every tile would get a missing
        // row and column at the seam with its neighbour.
        if (x == metadata.width()) {
            x--;
        }
        if (y == metadata.height()) {
            y--;
        }
        if (x < 0 || y < 0 || x >= metadata.width() || y >= metadata.height()) {
            return ElevationProvider.NO_DATA;
        }
        double value = sourcePixel(x, y);
        if (!Double.isFinite(value)) {
            return ElevationProvider.NO_DATA;
        }
        Double noData = metadata.noData();
        return noData != null && value == noData ? ElevationProvider.NO_DATA : value;
    }

    /** One degree cell of the raster, presented as a square grid the transcoder understands. */
    private final class CellSource implements DemSource {

        private final DemTileKey key;
        private final double[] row;

        private CellSource(DemTileKey key) {
            this.key = key;
            this.row = new double[samplesPerAxis];
        }

        @Override
        public DemTileKey key() {
            return key;
        }

        @Override
        public int width() {
            return samplesPerAxis;
        }

        @Override
        public int height() {
            return samplesPerAxis;
        }

        @Override
        public double[] readRow(int y) throws IOException {
            if (y < 0 || y >= samplesPerAxis) {
                throw new IndexOutOfBoundsException("Row " + y + " outside " + samplesPerAxis + "-row tile " + key);
            }
            double latitude = key.latDegree() + 1.0 - (double) y / (samplesPerAxis - 1);
            for (int x = 0; x < samplesPerAxis; x++) {
                double longitude = key.lonDegree() + (double) x / (samplesPerAxis - 1);
                row[x] = sample(latitude, longitude);
            }
            return row;
        }

        @Override
        public boolean needsFloat32() {
            return metadata.floatingPoint();
        }

        @Override
        public void close() {
            // The raster outlives every cell; GeoTiffDemFile owns it.
        }
    }
}
