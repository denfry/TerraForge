package dev.terraforge.cli.dem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * The handful of TIFF tags a GeoTIFF DEM is defined by, read straight from the first IFD.
 *
 * <p>Deliberately not read through {@code IIOMetadata}: the georeferencing lives in three plain
 * tags, and parsing them directly is a fixed hundred lines with no dependency on how a particular
 * ImageIO plugin shapes its metadata tree. Pixel data still goes through ImageIO, which is where
 * the compression codecs are.
 *
 * <p>Only north-up, axis-aligned, geographic (degree) rasters are accepted. A rotated or projected
 * GeoTIFF is rejected rather than silently misplaced by hundreds of kilometres.
 *
 * @param width         raster width in pixels
 * @param height        raster height in pixels
 * @param originLon     longitude of the outer edge of the top-left pixel
 * @param originLat     latitude of the outer edge of the top-left pixel
 * @param pixelSizeLon  degrees of longitude per pixel, always positive
 * @param pixelSizeLat  degrees of latitude per pixel, always positive (rows run north to south)
 * @param floatingPoint true when samples are IEEE floating point
 * @param noData        the raster's no-data value, or {@code null} when it declares none
 */
public record GeoTiffMetadata(int width, int height, double originLon, double originLat,
                              double pixelSizeLon, double pixelSizeLat, boolean floatingPoint,
                              Double noData) {

    private static final int TAG_IMAGE_WIDTH = 256;
    private static final int TAG_IMAGE_HEIGHT = 257;
    private static final int TAG_SAMPLE_FORMAT = 339;
    private static final int TAG_MODEL_PIXEL_SCALE = 33550;
    private static final int TAG_MODEL_TIEPOINT = 33922;
    private static final int TAG_MODEL_TRANSFORMATION = 34264;
    private static final int TAG_GDAL_NODATA = 42113;

    private static final int SAMPLE_FORMAT_IEEE_FLOAT = 3;

    /** Largest plausible degrees-per-pixel: anything coarser is not a DEM worth preparing. */
    private static final double MAX_PIXEL_SIZE_DEGREES = 1.0;

    public static GeoTiffMetadata read(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = read(channel, 0, 8);
            ByteOrder order = byteOrder(header);
            header.order(order);
            int magic = Short.toUnsignedInt(header.getShort(2));
            if (magic == 43) {
                throw new IOException("BigTIFF is not supported; convert with "
                        + "'gdal_translate -co BIGTIFF=NO input.tif output.tif'");
            }
            if (magic != 42) {
                throw new IOException("Not a TIFF file: bad magic number " + magic);
            }
            Map<Integer, Object> tags = readFirstIfd(channel, order, Integer.toUnsignedLong(header.getInt(4)));

            if (tags.containsKey(TAG_MODEL_TRANSFORMATION)) {
                throw new IOException("The raster uses a model transformation (rotated or sheared); "
                        + "reproject it north-up with 'gdalwarp -t_srs EPSG:4326'");
            }
            double[] scale = doubles(tags, TAG_MODEL_PIXEL_SCALE);
            double[] tiepoint = doubles(tags, TAG_MODEL_TIEPOINT);
            if (scale == null || tiepoint == null || scale.length < 2 || tiepoint.length < 6) {
                throw new IOException("The raster is not georeferenced: ModelPixelScale and "
                        + "ModelTiepoint tags are required. Convert with 'gdalwarp -t_srs EPSG:4326'");
            }
            int width = integer(tags, TAG_IMAGE_WIDTH);
            int height = integer(tags, TAG_IMAGE_HEIGHT);
            double pixelSizeLon = Math.abs(scale[0]);
            double pixelSizeLat = Math.abs(scale[1]);
            // A projected raster has pixel sizes in metres, so they are far larger than one degree.
            if (pixelSizeLon <= 0 || pixelSizeLat <= 0
                    || pixelSizeLon > MAX_PIXEL_SIZE_DEGREES || pixelSizeLat > MAX_PIXEL_SIZE_DEGREES) {
                throw new IOException("Pixel size " + pixelSizeLon + " x " + pixelSizeLat
                        + " is not in degrees; reproject to WGS84 with 'gdalwarp -t_srs EPSG:4326'");
            }
            // Tiepoint maps raster point (i, j, k) to model point (x, y, z).
            double originLon = tiepoint[3] - tiepoint[0] * pixelSizeLon;
            double originLat = tiepoint[4] + tiepoint[1] * pixelSizeLat;
            if (originLat > 90.0 + pixelSizeLat || originLat < -90.0
                    || originLon < -180.0 - pixelSizeLon || originLon > 180.0) {
                throw new IOException("The raster origin " + originLat + ", " + originLon
                        + " is outside WGS84; reproject with 'gdalwarp -t_srs EPSG:4326'");
            }
            return new GeoTiffMetadata(width, height, originLon, originLat, pixelSizeLon, pixelSizeLat,
                    integerOrDefault(tags, TAG_SAMPLE_FORMAT, 1) == SAMPLE_FORMAT_IEEE_FLOAT,
                    noData(tags));
        }
    }

    /** Geographic bounds of the raster. */
    public double southLatitude() {
        return originLat - height * pixelSizeLat;
    }

    public double eastLongitude() {
        return originLon + width * pixelSizeLon;
    }

    // --- TIFF parsing -------------------------------------------------------

    private static ByteOrder byteOrder(ByteBuffer header) throws IOException {
        String marker = new String(new byte[]{header.get(0), header.get(1)}, StandardCharsets.US_ASCII);
        return switch (marker) {
            case "II" -> ByteOrder.LITTLE_ENDIAN;
            case "MM" -> ByteOrder.BIG_ENDIAN;
            default -> throw new IOException("Not a TIFF file: byte-order marker '" + marker + "'");
        };
    }

    private static Map<Integer, Object> readFirstIfd(FileChannel channel, ByteOrder order, long offset)
            throws IOException {
        ByteBuffer count = read(channel, offset, 2).order(order);
        int entries = Short.toUnsignedInt(count.getShort(0));
        ByteBuffer directory = read(channel, offset + 2, entries * 12).order(order);
        Map<Integer, Object> tags = new HashMap<>();
        for (int i = 0; i < entries; i++) {
            int base = i * 12;
            int tag = Short.toUnsignedInt(directory.getShort(base));
            int type = Short.toUnsignedInt(directory.getShort(base + 2));
            long valueCount = Integer.toUnsignedLong(directory.getInt(base + 4));
            ByteBuffer value = ByteBuffer.wrap(new byte[4]).order(order);
            value.putInt(0, directory.getInt(base + 8));
            tags.put(tag, readValue(channel, order, type, valueCount, value));
        }
        return tags;
    }

    private static Object readValue(FileChannel channel, ByteOrder order, int type, long count,
                                    ByteBuffer inlineValue) throws IOException {
        int elementSize = switch (type) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11 -> 4;
            case 5, 10, 12 -> 8;
            default -> 0;
        };
        if (elementSize == 0 || count > Integer.MAX_VALUE / Math.max(1, elementSize)) {
            return null;
        }
        int bytes = (int) (count * elementSize);
        ByteBuffer data = bytes <= 4
                ? inlineValue
                : read(channel, Integer.toUnsignedLong(inlineValue.getInt(0)), bytes).order(order);
        return switch (type) {
            case 2 -> ascii(data, bytes);
            case 3 -> (long) Short.toUnsignedInt(data.getShort(0));
            case 4 -> Integer.toUnsignedLong(data.getInt(0));
            case 12 -> doubleArray(data, (int) count);
            default -> null;
        };
    }

    private static String ascii(ByteBuffer data, int bytes) {
        byte[] raw = new byte[bytes];
        data.get(0, raw);
        int end = bytes;
        while (end > 0 && raw[end - 1] == 0) {
            end--;
        }
        return new String(raw, 0, end, StandardCharsets.US_ASCII);
    }

    private static double[] doubleArray(ByteBuffer data, int count) {
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = data.getDouble(i * 8);
        }
        return values;
    }

    private static ByteBuffer read(FileChannel channel, long offset, int length) throws IOException {
        if (length <= 0 || offset < 0 || offset + length > channel.size()) {
            throw new IOException("Truncated TIFF: cannot read " + length + " bytes at " + offset);
        }
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) < 0) {
                throw new IOException("Truncated TIFF at offset " + offset);
            }
        }
        return buffer.flip();
    }

    private static double[] doubles(Map<Integer, Object> tags, int tag) {
        return tags.get(tag) instanceof double[] values ? values : null;
    }

    private static int integer(Map<Integer, Object> tags, int tag) throws IOException {
        if (tags.get(tag) instanceof Long value) {
            return Math.toIntExact(value);
        }
        throw new IOException("TIFF tag " + tag + " is missing");
    }

    private static int integerOrDefault(Map<Integer, Object> tags, int tag, int fallback) {
        return tags.get(tag) instanceof Long value ? value.intValue() : fallback;
    }

    private static Double noData(Map<Integer, Object> tags) {
        if (!(tags.get(TAG_GDAL_NODATA) instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isNaN(value) ? null : value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
