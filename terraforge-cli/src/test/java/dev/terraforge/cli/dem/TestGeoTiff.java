package dev.terraforge.cli.dem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Writes minimal but valid single-band GeoTIFFs for tests.
 *
 * <p>Hand-rolled rather than produced through ImageIO because the point of the exercise is the
 * geo-referencing tags -- ModelPixelScale, ModelTiepoint and the GeoKeyDirectory -- which no image
 * writer emits for us. A baseline uncompressed TIFF is a header, a strip of pixels and a sorted
 * list of tags, so building one here is cheaper than depending on a GIS stack in tests.
 */
final class TestGeoTiff {

    private static final short TYPE_SHORT = 3;
    private static final short TYPE_LONG = 4;
    private static final short TYPE_DOUBLE = 12;

    private static final int MODEL_TYPE_GEOGRAPHIC = 2;
    private static final int MODEL_TYPE_PROJECTED = 1;

    private final int width;
    private final int height;
    private final short[] samples;
    private double west = 8.0;
    private double north = 51.0;
    private double pixelSize = 0.1;
    private boolean pixelIsPoint = true;
    private boolean projected;
    private boolean georeferenced = true;

    private TestGeoTiff(int width, int height, short[] samples) {
        this.width = width;
        this.height = height;
        this.samples = samples;
    }

    /** A raster whose samples come from {@code value(row, column)}, north row first. */
    static TestGeoTiff of(int width, int height, Sampler value) {
        short[] samples = new short[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                samples[y * width + x] = value.at(y, x);
            }
        }
        return new TestGeoTiff(width, height, samples);
    }

    @FunctionalInterface
    interface Sampler {
        short at(int row, int column);
    }

    TestGeoTiff origin(double north, double west) {
        this.north = north;
        this.west = west;
        return this;
    }

    TestGeoTiff pixelSize(double degrees) {
        this.pixelSize = degrees;
        return this;
    }

    TestGeoTiff pixelIsArea() {
        this.pixelIsPoint = false;
        return this;
    }

    TestGeoTiff projected() {
        this.projected = true;
        return this;
    }

    TestGeoTiff withoutGeoreferencing() {
        this.georeferenced = false;
        return this;
    }

    Path writeTo(Path file) throws IOException {
        List<Entry> entries = new ArrayList<>();
        entries.add(Entry.longs(256, width));
        entries.add(Entry.longs(257, height));
        entries.add(Entry.shorts(258, 16));                     // BitsPerSample
        entries.add(Entry.shorts(259, 1));                      // Compression: none
        entries.add(Entry.shorts(262, 1));                      // Photometric: black is zero
        entries.add(Entry.shorts(277, 1));                      // SamplesPerPixel
        entries.add(Entry.longs(278, height));                  // RowsPerStrip: one strip
        entries.add(Entry.shorts(339, 2));                      // SampleFormat: signed integer

        if (georeferenced) {
            entries.add(Entry.doubles(33550, pixelSize, pixelSize, 0.0));
            entries.add(Entry.doubles(33922, 0.0, 0.0, 0.0, west, north, 0.0));
            entries.add(Entry.shorts(34735,
                    1, 1, 0, 2,                                 // GeoKeyDirectory header, 2 keys
                    1024, 0, 1, projected ? MODEL_TYPE_PROJECTED : MODEL_TYPE_GEOGRAPHIC,
                    1025, 0, 1, pixelIsPoint ? 2 : 1));
        }

        byte[] pixels = new byte[samples.length * 2];
        ByteBuffer pixelBuffer = ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) {
            pixelBuffer.putShort(sample);
        }

        int pixelOffset = 8;
        entries.add(Entry.longs(273, pixelOffset));             // StripOffsets
        entries.add(Entry.longs(279, pixels.length));           // StripByteCounts
        entries.sort(Comparator.comparingInt(entry -> entry.tag));

        // Values wider than four bytes live outside the IFD, between the pixels and the directory.
        int outOfLineStart = pixelOffset + pixels.length;
        int outOfLineBytes = 0;
        for (Entry entry : entries) {
            if (entry.value.length > 4) {
                entry.valueOffset = outOfLineStart + outOfLineBytes;
                outOfLineBytes += entry.value.length;
            }
        }
        int ifdOffset = outOfLineStart + outOfLineBytes;
        int total = ifdOffset + 2 + entries.size() * 12 + 4;

        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifdOffset);
        out.put(pixels);
        for (Entry entry : entries) {
            if (entry.value.length > 4) {
                out.position(entry.valueOffset);
                out.put(entry.value);
            }
        }
        out.position(ifdOffset);
        out.putShort((short) entries.size());
        for (Entry entry : entries) {
            out.putShort((short) entry.tag);
            out.putShort(entry.type);
            out.putInt(entry.count);
            if (entry.value.length > 4) {
                out.putInt(entry.valueOffset);
            } else {
                byte[] inline = new byte[4];
                System.arraycopy(entry.value, 0, inline, 0, entry.value.length);
                out.put(inline);
            }
        }
        out.putInt(0); // no next IFD

        Files.createDirectories(file.getParent());
        Files.write(file, out.array());
        return file;
    }

    private static final class Entry {
        final int tag;
        final short type;
        final int count;
        final byte[] value;
        int valueOffset;

        private Entry(int tag, short type, int count, byte[] value) {
            this.tag = tag;
            this.type = type;
            this.count = count;
            this.value = value;
        }

        static Entry shorts(int tag, int... values) {
            ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int value : values) {
                buffer.putShort((short) value);
            }
            return new Entry(tag, TYPE_SHORT, values.length, buffer.array());
        }

        static Entry longs(int tag, int... values) {
            ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int value : values) {
                buffer.putInt(value);
            }
            return new Entry(tag, TYPE_LONG, values.length, buffer.array());
        }

        static Entry doubles(int tag, double... values) {
            ByteBuffer buffer = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (double value : values) {
                buffer.putDouble(value);
            }
            return new Entry(tag, TYPE_DOUBLE, values.length, buffer.array());
        }
    }
}
