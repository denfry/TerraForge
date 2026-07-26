package dev.terraforge.cli.dem;

import com.twelvemonkeys.imageio.metadata.Directory;
import com.twelvemonkeys.imageio.metadata.Entry;
import com.twelvemonkeys.imageio.metadata.tiff.TIFFReader;
import java.awt.Rectangle;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * A geographic (WGS84) GeoTIFF elevation raster -- Copernicus GLO-30/GLO-90, ASTER GDEM, and the
 * GeoTIFF exports of most national datasets.
 *
 * <p>Only north-up rasters in geographic coordinates are accepted. A projected GeoTIFF is rejected
 * with an explanation rather than resampled as if its metres were degrees; reprojecting is a job for
 * {@code gdalwarp}, and doing it badly here would produce terrain that is subtly wrong everywhere.
 *
 * <p>Pixels are read in horizontal strips so a 30 GB source does not have to fit in memory.
 */
final class GeoTiffRaster extends GridRaster {

    private static final int TAG_MODEL_PIXEL_SCALE = 33550;
    private static final int TAG_MODEL_TIEPOINT = 33922;
    private static final int TAG_MODEL_TRANSFORMATION = 34264;
    private static final int TAG_GEO_KEY_DIRECTORY = 34735;
    private static final int TAG_GDAL_NODATA = 42113;
    private static final int TAG_SAMPLE_FORMAT = 339;
    private static final int SAMPLE_FORMAT_FLOAT = 3;

    private static final int GEO_KEY_MODEL_TYPE = 1024;
    private static final int GEO_KEY_RASTER_TYPE = 1025;
    private static final int MODEL_TYPE_GEOGRAPHIC = 2;
    private static final int RASTER_PIXEL_IS_POINT = 2;

    /** Rows held in memory at once. 512 rows of a 3601-wide float raster is about 7 MB. */
    private static final int STRIP_ROWS = 512;

    private final Path file;
    private final ImageInputStream stream;
    private final ImageReader reader;
    private final double noData;
    private final boolean floatSource;

    private Raster strip;
    private int stripFirstRow = -1;

    private GeoTiffRaster(Path file, ImageInputStream stream, ImageReader reader, Geometry geometry,
                          double noData, boolean floatSource) {
        super(geometry.centerLat0, geometry.centerLon0, geometry.latStep, geometry.lonStep,
                geometry.width, geometry.height);
        this.file = file;
        this.stream = stream;
        this.reader = reader;
        this.noData = noData;
        this.floatSource = floatSource;
    }

    static boolean matches(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".tif") || name.endsWith(".tiff");
    }

    static GeoTiffRaster open(Path file) throws IOException {
        ImageInputStream stream = ImageIO.createImageInputStream(file.toFile());
        if (stream == null) {
            throw new IOException("cannot open " + file + " as an image stream");
        }
        ImageReader reader = null;
        try {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IOException("no TIFF reader available for " + file.getFileName());
            }
            reader = readers.next();
            reader.setInput(stream, true, true);

            int width = reader.getWidth(0);
            int height = reader.getHeight(0);

            stream.seek(0);
            Directory ifd = new TIFFReader().read(stream);
            Geometry geometry = geometryOf(file, ifd, width, height);
            double noData = noDataOf(ifd);

            return new GeoTiffRaster(file, stream, reader, geometry, noData, isFloatSource(ifd));
        } catch (IOException | RuntimeException e) {
            if (reader != null) {
                reader.dispose();
            }
            stream.close();
            throw e;
        }
    }

    private record Geometry(double centerLat0, double centerLon0, double latStep, double lonStep,
                            int width, int height) {
    }

    private static Geometry geometryOf(Path file, Directory ifd, int width, int height) throws IOException {
        String name = file.getFileName().toString();
        requireGeographic(name, ifd);

        double[] scale = doubles(ifd.getEntryById(TAG_MODEL_PIXEL_SCALE));
        double[] tiepoint = doubles(ifd.getEntryById(TAG_MODEL_TIEPOINT));
        double[] transform = doubles(ifd.getEntryById(TAG_MODEL_TRANSFORMATION));

        double west;
        double north;
        double lonStep;
        double latStep;
        if (scale != null && scale.length >= 2 && tiepoint != null && tiepoint.length >= 6) {
            // Tiepoint maps raster point (i,j) to model (x,y); anything but (0,0) is unusual enough
            // that guessing would be worse than refusing.
            if (tiepoint[0] != 0.0 || tiepoint[1] != 0.0) {
                throw new IOException(name + " has a tiepoint that is not at raster (0,0); "
                        + "re-export it with gdal_translate");
            }
            west = tiepoint[3];
            north = tiepoint[4];
            lonStep = scale[0];
            latStep = -Math.abs(scale[1]);
        } else if (transform != null && transform.length >= 16) {
            if (transform[1] != 0.0 || transform[4] != 0.0) {
                throw new IOException(name + " is rotated; only north-up rasters are supported. "
                        + "Re-project it with gdalwarp.");
            }
            west = transform[3];
            north = transform[7];
            lonStep = transform[0];
            latStep = transform[5];
        } else {
            throw new IOException(name + " carries no geo-referencing (no ModelPixelScale/ModelTiepoint "
                    + "and no ModelTransformation), so TerraForge cannot tell where on Earth it is");
        }

        if (lonStep <= 0 || latStep >= 0) {
            throw new IOException(name + " is not north-up west-to-east; re-project it with gdalwarp");
        }
        if (Math.abs(west) > 180.0 || Math.abs(north) > 90.0) {
            throw new IOException(name + " has coordinates outside degrees (origin " + west + ", " + north
                    + "); it is probably projected. TerraForge reads WGS84 geographic rasters only -- "
                    + "convert it with: gdalwarp -t_srs EPSG:4326 in.tif out.tif");
        }

        // Pixel-is-area puts the tiepoint on the outer corner; pixel-is-point puts it on the centre.
        boolean pixelIsPoint = rasterType(ifd) == RASTER_PIXEL_IS_POINT;
        double centerLon0 = pixelIsPoint ? west : west + lonStep / 2.0;
        double centerLat0 = pixelIsPoint ? north : north + latStep / 2.0;
        return new Geometry(centerLat0, centerLon0, latStep, lonStep, width, height);
    }

    private static void requireGeographic(String name, Directory ifd) throws IOException {
        int[] keys = geoKeys(ifd);
        if (keys == null) {
            return; // no GeoKeyDirectory: fall back to the coordinate range check above
        }
        int modelType = geoKeyValue(keys, GEO_KEY_MODEL_TYPE, MODEL_TYPE_GEOGRAPHIC);
        if (modelType != MODEL_TYPE_GEOGRAPHIC) {
            throw new IOException(name + " is a projected GeoTIFF (ModelType " + modelType + "). "
                    + "TerraForge reads WGS84 geographic rasters only -- convert it with: "
                    + "gdalwarp -t_srs EPSG:4326 " + name + " out.tif");
        }
    }

    private static int rasterType(Directory ifd) {
        int[] keys = geoKeys(ifd);
        return keys == null ? 1 : geoKeyValue(keys, GEO_KEY_RASTER_TYPE, 1);
    }

    /**
     * The GeoKeyDirectory is a flat short array: a four-entry header followed by four-short records
     * of (key, tagLocation, count, value). Only keys stored in line (tagLocation 0) are read here --
     * the two keys TerraForge cares about always are.
     */
    private static int geoKeyValue(int[] keys, int wanted, int fallback) {
        for (int i = 4; i + 3 < keys.length; i += 4) {
            if (keys[i] == wanted && keys[i + 1] == 0) {
                return keys[i + 3];
            }
        }
        return fallback;
    }

    private static int[] geoKeys(Directory ifd) {
        double[] values = doubles(ifd.getEntryById(TAG_GEO_KEY_DIRECTORY));
        if (values == null || values.length < 4) {
            return null;
        }
        int[] keys = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            keys[i] = (int) values[i];
        }
        return keys;
    }

    /**
     * SampleFormat 3 is IEEE floating point. Read from the IFD rather than from the reader's image
     * type, because ImageIO has no colour model for a single-band float raster and refuses to
     * describe one -- {@code readRaster} still works, which is all the transcoder needs.
     */
    private static boolean isFloatSource(Directory ifd) {
        double[] sampleFormat = doubles(ifd.getEntryById(TAG_SAMPLE_FORMAT));
        return sampleFormat != null && sampleFormat.length > 0 && (int) sampleFormat[0] == SAMPLE_FORMAT_FLOAT;
    }

    private static double noDataOf(Directory ifd) {
        Entry entry = ifd.getEntryById(TAG_GDAL_NODATA);
        if (entry == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(String.valueOf(entry.getValue()).trim());
        } catch (NumberFormatException e) {
            return Double.NaN; // "nan" and friends already mean no-data
        }
    }

    /** TIFF values arrive as whichever primitive array the field type implies; normalise them. */
    private static double[] doubles(Entry entry) {
        if (entry == null) {
            return null;
        }
        Object value = entry.getValue();
        return switch (value) {
            case double[] v -> v;
            case float[] v -> {
                double[] out = new double[v.length];
                for (int i = 0; i < v.length; i++) {
                    out[i] = v[i];
                }
                yield out;
            }
            case int[] v -> {
                double[] out = new double[v.length];
                for (int i = 0; i < v.length; i++) {
                    out[i] = v[i];
                }
                yield out;
            }
            case long[] v -> {
                double[] out = new double[v.length];
                for (int i = 0; i < v.length; i++) {
                    out[i] = v[i];
                }
                yield out;
            }
            case short[] v -> {
                double[] out = new double[v.length];
                for (int i = 0; i < v.length; i++) {
                    out[i] = v[i];
                }
                yield out;
            }
            case Number n -> new double[]{n.doubleValue()};
            case null, default -> null;
        };
    }

    @Override
    protected double rawSample(int x, int y) {
        if (x < 0 || y < 0 || x >= width() || y >= height()) {
            return Double.NaN;
        }
        Raster rows = stripFor(y);
        if (rows == null) {
            return Double.NaN;
        }
        double value = rows.getSampleDouble(rows.getMinX() + x, rows.getMinY() + (y - stripFirstRow), 0);
        if (value == noData || Double.isNaN(value)) {
            return Double.NaN;
        }
        // Sources vary in which sentinel they use for voids; these are never real elevations.
        return value <= -30000.0 || value >= 30000.0 ? Double.NaN : value;
    }

    private Raster stripFor(int y) {
        if (strip != null && y >= stripFirstRow && y < stripFirstRow + strip.getHeight()) {
            return strip;
        }
        int first = (y / STRIP_ROWS) * STRIP_ROWS;
        int rows = Math.min(STRIP_ROWS, height() - first);
        try {
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceRegion(new Rectangle(0, first, width(), rows));
            strip = reader.readRaster(0, param);
            stripFirstRow = first;
            return strip;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(
                    "failed to read rows " + first + "-" + (first + rows) + " of " + file.getFileName(), e);
        }
    }

    @Override
    public boolean needsFloatPrecision() {
        return floatSource;
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "%s (GeoTIFF, %dx%d, ~%.0f m/px, %s)",
                file.getFileName(), width(), height(), resolutionMeters(),
                floatSource ? "float" : "integer");
    }

    @Override
    public void close() throws IOException {
        strip = null;
        reader.dispose();
        stream.close();
    }
}
