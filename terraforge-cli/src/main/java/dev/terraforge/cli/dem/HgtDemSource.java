package dev.terraforge.cli.dem;

import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.geo.dem.DemTileKey;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * SRTM {@code .hgt} tiles: big-endian int16 metres, square, no header at all.
 *
 * <p>Everything the format does not state is derived: the grid size from the file length (1201 for
 * three arc-second data, 3601 for one arc-second), and the covered cell from the file name, which
 * is why {@link DemTileKey} uses the same naming scheme.
 *
 * <p>{@code -32768} marks a void in SRTM and becomes {@link ElevationProvider#NO_DATA} -- voids are
 * carried through preparation rather than filled, so the generator can tell missing from flat.
 */
public final class HgtDemSource implements DemSource {

    private static final short HGT_VOID = -32768;

    private final DemTileKey key;
    private final int size;
    private final FileChannel channel;
    private final ByteBuffer rowBytes;
    private final double[] row;

    private HgtDemSource(DemTileKey key, int size, FileChannel channel) {
        this.key = key;
        this.size = size;
        this.channel = channel;
        this.rowBytes = ByteBuffer.allocate(size * 2).order(ByteOrder.BIG_ENDIAN);
        this.row = new double[size];
    }

    /**
     * Opens an {@code .hgt} file, e.g. {@code N50E008.hgt}.
     *
     * @throws IOException when the name is not a tile name, or the length is not a square int16 grid
     */
    public static HgtDemSource open(Path file) throws IOException {
        String name = file.getFileName().toString();
        String stem = name.endsWith(".hgt") ? name.substring(0, name.length() - 4) : name;
        DemTileKey key;
        try {
            key = DemTileKey.parse(stem);
        } catch (IllegalArgumentException e) {
            throw new IOException("Cannot tell which cell " + name
                    + " covers: .hgt files must be named like N50E008.hgt", e);
        }

        long length = Files.size(file);
        long samples = length / 2;
        int size = (int) Math.round(Math.sqrt((double) samples));
        if (length % 2 != 0 || (long) size * size != samples || size < 2) {
            throw new IOException("Not a square int16 grid: " + name + " is " + length + " bytes");
        }
        return new HgtDemSource(key, size, FileChannel.open(file, StandardOpenOption.READ));
    }

    @Override
    public DemTileKey key() {
        return key;
    }

    @Override
    public int width() {
        return size;
    }

    @Override
    public int height() {
        return size;
    }

    @Override
    public double[] readRow(int y) throws IOException {
        if (y < 0 || y >= size) {
            throw new IndexOutOfBoundsException("Row " + y + " outside " + size + "-row tile " + key);
        }
        rowBytes.clear();
        long offset = (long) y * size * 2;
        while (rowBytes.hasRemaining()) {
            if (channel.read(rowBytes, offset + rowBytes.position()) < 0) {
                throw new IOException("Truncated .hgt tile " + key + " at row " + y);
            }
        }
        rowBytes.flip();
        for (int x = 0; x < size; x++) {
            short raw = rowBytes.getShort(x * 2);
            row[x] = raw == HGT_VOID ? ElevationProvider.NO_DATA : raw;
        }
        return row;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
