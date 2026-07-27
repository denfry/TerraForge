package dev.terraforge.cli.dem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the smallest GeoTIFF that is still a real one: uncompressed, single strip, signed 16-bit
 * samples, with the three georeferencing tags a DEM needs.
 *
 * <p>Hand-assembled rather than produced through ImageIO, because the point of the test is to read
 * bytes a GDAL-produced file would actually contain -- including the tags ImageIO does not surface.
 */
final class TestGeoTiff {

    private static final int TYPE_SHORT = 3;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_ASCII = 2;
    private static final int TYPE_DOUBLE = 12;

    private TestGeoTiff() {
    }

    /**
     * @param originLat latitude of the outer edge of the top-left pixel
     * @param originLon longitude of the outer edge of the top-left pixel
     * @param pixelSize degrees per pixel on both axes
     * @param samples   row-major elevations, north row first
     * @param noData    optional GDAL_NODATA value
     */
    static void write(Path file, int width, int height, double originLat, double originLon,
                      double pixelSize, short[] samples, String noData) throws IOException {
        if (samples.length != width * height) {
            throw new IllegalArgumentException("samples must be width * height");
        }
        int imageBytes = width * height * 2;
        int imageOffset = 8;
        int scaleOffset = imageOffset + imageBytes;
        int tiepointOffset = scaleOffset + 3 * 8;
        int noDataOffset = tiepointOffset + 6 * 8;
        byte[] noDataBytes = noData == null
                ? new byte[0]
                : (noData + "\0").getBytes(StandardCharsets.US_ASCII);
        int ifdOffset = noDataOffset + noDataBytes.length;

        List<int[]> entries = new ArrayList<>();
        entries.add(entry(256, TYPE_LONG, 1, width));
        entries.add(entry(257, TYPE_LONG, 1, height));
        entries.add(entry(258, TYPE_SHORT, 1, 16));
        entries.add(entry(259, TYPE_SHORT, 1, 1));
        entries.add(entry(262, TYPE_SHORT, 1, 1));
        entries.add(entry(273, TYPE_LONG, 1, imageOffset));
        entries.add(entry(277, TYPE_SHORT, 1, 1));
        entries.add(entry(278, TYPE_LONG, 1, height));
        entries.add(entry(279, TYPE_LONG, 1, imageBytes));
        entries.add(entry(339, TYPE_SHORT, 1, 2));
        entries.add(entry(33550, TYPE_DOUBLE, 3, scaleOffset));
        entries.add(entry(33922, TYPE_DOUBLE, 6, tiepointOffset));
        if (noData != null) {
            entries.add(entry(42113, TYPE_ASCII, noDataBytes.length, noDataOffset));
        }

        int total = ifdOffset + 2 + entries.size() * 12 + 4;
        ByteBuffer buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'I').put((byte) 'I');
        buffer.putShort((short) 42);
        buffer.putInt(ifdOffset);
        for (short sample : samples) {
            buffer.putShort(sample);
        }
        buffer.putDouble(pixelSize).putDouble(pixelSize).putDouble(0.0);
        buffer.putDouble(0).putDouble(0).putDouble(0);
        buffer.putDouble(originLon).putDouble(originLat).putDouble(0);
        buffer.put(noDataBytes);

        buffer.putShort((short) entries.size());
        for (int[] entry : entries) {
            buffer.putShort((short) entry[0]);
            buffer.putShort((short) entry[1]);
            buffer.putInt(entry[2]);
            if (entry[1] == TYPE_SHORT) {
                // A SHORT value sits in the first two bytes of the four-byte value field.
                buffer.putShort((short) entry[3]).putShort((short) 0);
            } else {
                buffer.putInt(entry[3]);
            }
        }
        buffer.putInt(0);

        Files.write(file, buffer.array());
    }

    private static int[] entry(int tag, int type, int count, int value) {
        return new int[]{tag, type, count, value};
    }
}
