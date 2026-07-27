package dev.terraforge.geo.dem;

import dev.terraforge.core.data.ElevationProvider;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Writes one {@code .tfdem} tile, row by row.
 *
 * <p>Row streaming rather than a whole-grid array: a 3601x3601 float32 tile is 50 MB, and the CLI
 * transcodes dozens of them: holding one row at a time keeps preparation memory flat regardless of
 * tile size or thread count.
 *
 * <p>The file is written to a sibling {@code .tmp} and moved into place on {@link #close()}, so an
 * interrupted preparation leaves no half-written tile that the server would later map and trust.
 */
public final class TfDemWriter implements AutoCloseable {

    private final TfDemHeader header;
    private final Path target;
    private final Path temporary;
    private final FileChannel channel;
    private final ByteBuffer rowBuffer;

    private int rowsWritten;
    private boolean failed;

    private TfDemWriter(TfDemHeader header, Path target, Path temporary, FileChannel channel) {
        this.header = header;
        this.target = target;
        this.temporary = temporary;
        this.channel = channel;
        this.rowBuffer = ByteBuffer
                .allocate(header.bytesPerSample() * header.width())
                .order(ByteOrder.BIG_ENDIAN);
    }

    /**
     * Opens a writer for {@code directory/<key>.tfdem}, creating the directory when missing.
     *
     * @param header grid geometry and encoding; its key decides the file name
     */
    public static TfDemWriter create(Path directory, TfDemHeader header) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(header.key().fileName());
        Path temporary = directory.resolve(header.key().fileName() + ".tmp");
        FileChannel channel = FileChannel.open(temporary,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            channel.write(header.toBuffer());
        } catch (IOException e) {
            channel.close();
            Files.deleteIfExists(temporary);
            throw e;
        }
        return new TfDemWriter(header, target, temporary, channel);
    }

    public TfDemHeader header() {
        return header;
    }

    /**
     * Appends one row of samples, in metres, ordered west to east. Rows must be written north to
     * south, matching the on-disk order.
     *
     * <p>{@link ElevationProvider#NO_DATA} (NaN) is written as the header's sentinel. For int16
     * tiles, values are rounded to the nearest metre and values outside the encodable range are
     * rejected rather than silently wrapping.
     */
    public void writeRow(double[] metersWestToEast) throws IOException {
        if (metersWestToEast.length != header.width()) {
            throw new IllegalArgumentException(
                    "Row length " + metersWestToEast.length + " != tile width " + header.width());
        }
        if (rowsWritten >= header.height()) {
            throw new IllegalStateException("Tile already has all " + header.height() + " rows");
        }
        rowBuffer.clear();
        for (double meters : metersWestToEast) {
            encode(meters);
        }
        rowBuffer.flip();
        try {
            while (rowBuffer.hasRemaining()) {
                channel.write(rowBuffer);
            }
        } catch (IOException e) {
            failed = true;
            throw e;
        }
        rowsWritten++;
    }

    private void encode(double meters) {
        switch (header.encoding()) {
            case TfDemFormat.ENCODING_INT16 -> {
                if (ElevationProvider.isNoData(meters)) {
                    rowBuffer.putShort((short) header.noDataRaw());
                    return;
                }
                long raw = Math.round(meters * header.scaleDenominator() / (double) header.scaleNumerator());
                if (raw < Short.MIN_VALUE + 1 || raw > Short.MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "Elevation " + meters + " m does not fit int16 encoding; use --encoding float32");
                }
                rowBuffer.putShort((short) raw);
            }
            case TfDemFormat.ENCODING_FLOAT32 ->
                    rowBuffer.putFloat(ElevationProvider.isNoData(meters) ? Float.NaN : (float) meters);
            default -> throw new IllegalStateException("Unsupported encoding: " + header.encoding());
        }
    }

    /**
     * Flushes and publishes the tile.
     *
     * @throws IOException when fewer rows were written than the header declares -- a short tile is
     *                     never published
     */
    @Override
    public void close() throws IOException {
        try (channel) {
            if (!failed && rowsWritten != header.height()) {
                failed = true;
                throw new IOException("Tile " + header.key() + " has " + rowsWritten
                        + " of " + header.height() + " rows");
            }
        } finally {
            if (failed) {
                Files.deleteIfExists(temporary);
            } else {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }
}
