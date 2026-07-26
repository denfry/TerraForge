package dev.terraforge.cli.dem;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * An SRTM {@code .hgt} tile: big-endian 16-bit metres, square, one degree per side.
 *
 * <p>The format carries no header at all -- the file name gives the south-west corner and the file
 * size gives the resolution. Voids are {@code -32768}, which SRTM leaves in steep terrain and which
 * must stay no-data rather than becoming a 32 km deep hole.
 */
final class HgtRaster extends GridRaster {

    static final short VOID = -32768;

    private final Path file;
    private final FileChannel channel;
    private final ShortBuffer samples;
    private final int side;

    private HgtRaster(Path file, FileChannel channel, ShortBuffer samples, int side,
                      int latDegree, int lonDegree) {
        super(latDegree + 1.0, lonDegree, -1.0 / (side - 1), 1.0 / (side - 1), side, side);
        this.file = file;
        this.channel = channel;
        this.samples = samples;
        this.side = side;
    }

    static boolean matches(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".hgt");
    }

    static HgtRaster open(Path file) throws IOException {
        String name = file.getFileName().toString();
        int[] corner = parseCorner(name);
        if (corner == null) {
            throw new IOException("cannot tell which degree cell " + name
                    + " covers; SRTM HGT files must be named like N50E008.hgt");
        }
        long size = Files.size(file);
        if (size % 2 != 0) {
            throw new IOException(name + " has an odd byte count (" + size + "), so it is not 16-bit HGT");
        }
        long samples = size / 2;
        int side = (int) Math.round(Math.sqrt((double) samples));
        if ((long) side * side != samples || side < 2) {
            throw new IOException(name + " is not square: " + samples + " samples");
        }

        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        try {
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            mapped.order(ByteOrder.BIG_ENDIAN);
            return new HgtRaster(file, channel, mapped.asShortBuffer(), side, corner[0], corner[1]);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /** {@code N50E008.hgt} and the SRTMGL1 variant {@code N50E008.SRTMGL1.hgt}. */
    static int[] parseCorner(String fileName) {
        String base = fileName.toUpperCase(Locale.ROOT);
        int start = -1;
        for (int i = 0; i + 7 <= base.length(); i++) {
            char lat = base.charAt(i);
            char lon = base.charAt(i + 3);
            if ((lat == 'N' || lat == 'S') && (lon == 'E' || lon == 'W')
                    && digits(base, i + 1, 2) && digits(base, i + 4, 3)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int lat = Integer.parseInt(base, start + 1, start + 3, 10);
        int lon = Integer.parseInt(base, start + 4, start + 7, 10);
        return new int[]{
                base.charAt(start) == 'S' ? -lat : lat,
                base.charAt(start + 3) == 'W' ? -lon : lon,
        };
    }

    private static boolean digits(String s, int from, int count) {
        for (int i = from; i < from + count; i++) {
            if (i >= s.length() || !Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected double rawSample(int x, int y) {
        if (x < 0 || y < 0 || x >= side || y >= side) {
            return Double.NaN;
        }
        short raw = samples.get(y * side + x);
        return raw == VOID ? Double.NaN : raw;
    }

    @Override
    public boolean needsFloatPrecision() {
        return false; // HGT is whole metres by definition
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "%s (SRTM HGT, %dx%d, ~%.0f m/px)",
                file.getFileName(), side, side, resolutionMeters());
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
