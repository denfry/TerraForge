package dev.terraforge.geo.landcover;

import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Compact, versioned prepared land-cover grid used by the server runtime. */
public final class LandcoverGridFile {

    private static final int MAGIC = 0x54464C43; // TFLC
    private static final short VERSION = 1;
    private static final int MAX_CELLS = 64 * 1024 * 1024;

    /** magic + version + four extent doubles + width + height. */
    public static final int HEADER_BYTES = 4 + 2 + 8 * 4 + 4 + 4;

    private LandcoverGridFile() {
    }

    /**
     * Extent and shape of a prepared grid, read without its samples.
     *
     * <p>Cataloguing a directory this way is what lets a planet-wide world start: the runtime learns
     * where every grid is from {@value #HEADER_BYTES} bytes per file and loads the samples only for
     * the cells that are actually generated.
     */
    public record Header(double south, double west, double north, double east, int width, int height) {

        public long sampleBytes() {
            return (long) width * height;
        }
    }

    public static void write(Path target, double south, double west, double north, double east,
                             int width, int height, LandcoverClass[] values) throws IOException {
        validate(south, west, north, east, width, height, values.length);
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {
            output.writeInt(MAGIC);
            output.writeShort(VERSION);
            output.writeDouble(south);
            output.writeDouble(west);
            output.writeDouble(north);
            output.writeDouble(east);
            output.writeInt(width);
            output.writeInt(height);
            for (LandcoverClass value : values) {
                output.writeByte(value.ordinal());
            }
        }
    }

    /** Reads only the extent and shape; the samples are not touched. */
    public static Header readHeader(Path source) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(source), HEADER_BYTES))) {
            return header(source, input);
        } catch (EOFException exception) {
            throw new IOException(source + " is truncated", exception);
        }
    }

    public static GridLandcoverProvider read(Path source) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(source)))) {
            Header header = header(source, input);
            int cells = checkedCellCount(header.width(), header.height());
            byte[] ordinals = new byte[cells];
            input.readFully(ordinals);
            int classCount = LandcoverClass.values().length;
            for (byte ordinal : ordinals) {
                if (ordinal < 0 || ordinal >= classCount) {
                    throw new IOException(source + " contains an unknown land-cover class");
                }
            }
            if (input.read() != -1) {
                throw new IOException(source + " has trailing data");
            }
            return new GridLandcoverProvider(header.south(), header.west(), header.north(), header.east(),
                    header.width(), header.height(), ordinals);
        } catch (EOFException exception) {
            throw new IOException(source + " is truncated", exception);
        }
    }

    private static Header header(Path source, DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC) {
            throw new IOException(source + " is not a TerraForge land-cover grid");
        }
        if (input.readShort() != VERSION) {
            throw new IOException(source + " uses an unsupported land-cover format version");
        }
        double south = input.readDouble();
        double west = input.readDouble();
        double north = input.readDouble();
        double east = input.readDouble();
        int width = input.readInt();
        int height = input.readInt();
        validate(south, west, north, east, width, height, checkedCellCount(width, height));
        return new Header(south, west, north, east, width, height);
    }

    private static void validate(double south, double west, double north, double east,
                                 int width, int height, int valueCount) throws IOException {
        if (!Double.isFinite(south) || !Double.isFinite(west) || !Double.isFinite(north) || !Double.isFinite(east)
                || south < -90 || north > 90 || west < -180 || east > 180 || south >= north || west >= east
                || valueCount != checkedCellCount(width, height)) {
            throw new IOException("Invalid prepared land-cover grid");
        }
    }

    private static int checkedCellCount(int width, int height) throws IOException {
        if (width < 1 || height < 1) {
            throw new IOException("Land-cover grid dimensions must be positive");
        }
        try {
            int cells = Math.multiplyExact(width, height);
            if (cells > MAX_CELLS) {
                throw new IOException("Land-cover grid exceeds the 64 MiB runtime limit");
            }
            return cells;
        } catch (ArithmeticException exception) {
            throw new IOException("Land-cover grid dimensions overflow", exception);
        }
    }
}
