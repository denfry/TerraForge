package dev.terraforge.geo.dem;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes one {@code .tfdem} tile, row by row from the north-west corner.
 *
 * <p>Lives beside the reader on purpose: a format with the writer in a different module drifts. It
 * is used by the CLI, never by the server -- the runtime only ever reads tiles.
 *
 * <p>Writing goes to a temporary file that is atomically moved into place on {@link #close()}, so an
 * interrupted preparation run can never leave a half-written tile that the server would happily
 * memory-map and sample as terrain.
 */
public final class TfDemWriter implements AutoCloseable {

    private final TfDemHeader header;
    private final Path target;
    private final Path temp;
    private final OutputStream out;
    private final ByteBuffer row;

    private int rowsWritten;
    private boolean failed;

    public TfDemWriter(Path directory, TfDemHeader header) throws IOException {
        this.header = header;
        this.target = directory.resolve(header.key().fileName());
        Files.createDirectories(directory);
        this.temp = Files.createTempFile(directory, header.key().toString(), ".tfdem.tmp");
        this.out = new BufferedOutputStream(Files.newOutputStream(temp), 1 << 16);
        this.row = ByteBuffer
                .allocate(TfDemFormat.bytesPerSample(header.encoding()) * header.width())
                .order(ByteOrder.BIG_ENDIAN);
        out.write(header.toBuffer().array());
    }

    public TfDemHeader header() {
        return header;
    }

    public Path target() {
        return target;
    }

    /**
     * Appends one full row of elevations in metres, west to east.
     *
     * <p>Rows must be supplied north to south. {@code NaN} means "no data" and is written as the
     * header's sentinel.
     */
    public void writeRow(double[] metres) throws IOException {
        if (metres.length != header.width()) {
            throw new IllegalArgumentException(
                    "row has " + metres.length + " samples, tile width is " + header.width());
        }
        if (rowsWritten >= header.height()) {
            throw new IllegalStateException("tile already has all " + header.height() + " rows");
        }
        row.clear();
        double perRaw = header.metresPerRaw();
        for (double metre : metres) {
            if (header.encoding() == TfDemFormat.ENCODING_INT16) {
                row.putShort(Double.isNaN(metre)
                        ? (short) header.noDataRaw()
                        : clampToShort(Math.round(metre / perRaw)));
            } else {
                row.putFloat(Double.isNaN(metre)
                        ? Float.intBitsToFloat(header.noDataRaw())
                        : (float) (metre / perRaw));
            }
        }
        out.write(row.array(), 0, row.position());
        rowsWritten++;
    }

    /**
     * Elevation values beyond the int16 range are the sign of a broken source, not of real terrain:
     * clamp so one bad sample cannot wrap around into a mountain.
     */
    private static short clampToShort(long raw) {
        if (raw <= TfDemFormat.INT16_NO_DATA) {
            return TfDemFormat.INT16_NO_DATA + 1;
        }
        return raw > Short.MAX_VALUE ? Short.MAX_VALUE : (short) raw;
    }

    /** Marks the tile as unusable, so {@link #close()} discards it instead of publishing it. */
    public void abort() {
        failed = true;
    }

    @Override
    public void close() throws IOException {
        try {
            out.close();
            if (failed) {
                return;
            }
            if (rowsWritten != header.height()) {
                failed = true;
                throw new IOException("incomplete tile " + header.key() + ": wrote " + rowsWritten
                        + " of " + header.height() + " rows");
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
