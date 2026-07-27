package dev.terraforge.geo.dem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * The 64-byte header of a {@code .tfdem} file, parsed and validated.
 *
 * <p>Validation happens here, once per tile load, so every other class may assume a header is
 * consistent with the file it came from. A tile with a bad magic, an unknown encoding or a length
 * that disagrees with its grid is rejected at load rather than producing nonsense terrain.
 *
 * @param version         format version
 * @param encoding        {@link TfDemFormat#ENCODING_INT16} or {@link TfDemFormat#ENCODING_FLOAT32}
 * @param width           samples per row
 * @param height          rows
 * @param southLatitude   south edge, integer degrees
 * @param westLongitude   west edge, integer degrees
 * @param noDataRaw       sentinel, as a raw sample value
 * @param scaleNumerator  raw * numerator / denominator = metres
 * @param scaleDenominator see {@code scaleNumerator}
 */
public record TfDemHeader(
        int version,
        int encoding,
        int width,
        int height,
        int southLatitude,
        int westLongitude,
        int noDataRaw,
        int scaleNumerator,
        int scaleDenominator) {

    public TfDemHeader {
        if (version != TfDemFormat.VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported .tfdem version " + version + " (expected " + TfDemFormat.VERSION + ")");
        }
        TfDemFormat.bytesPerSample(encoding);
        if (width < 2 || height < 2) {
            throw new IllegalArgumentException("Grid must be at least 2x2, got " + width + "x" + height);
        }
        if (southLatitude < -90 || southLatitude > 89) {
            throw new IllegalArgumentException("South edge out of range: " + southLatitude);
        }
        if (westLongitude < -180 || westLongitude > 179) {
            throw new IllegalArgumentException("West edge out of range: " + westLongitude);
        }
        if (scaleDenominator == 0) {
            throw new IllegalArgumentException("Vertical scale denominator must not be zero");
        }
    }

    /** Header for a metre-precision int16 tile, the default preparation output. */
    public static TfDemHeader int16(DemTileKey key, int width, int height) {
        return new TfDemHeader(TfDemFormat.VERSION, TfDemFormat.ENCODING_INT16, width, height,
                key.latDegree(), key.lonDegree(), TfDemFormat.INT16_NO_DATA, 1, 1);
    }

    /** Header for a float32 tile, used for sub-metre precision or merged bathymetry. */
    public static TfDemHeader float32(DemTileKey key, int width, int height) {
        return new TfDemHeader(TfDemFormat.VERSION, TfDemFormat.ENCODING_FLOAT32, width, height,
                key.latDegree(), key.lonDegree(), 0, 1, 1);
    }

    public DemTileKey key() {
        return new DemTileKey(southLatitude, westLongitude);
    }

    public int bytesPerSample() {
        return TfDemFormat.bytesPerSample(encoding);
    }

    public long expectedFileSize() {
        return TfDemFormat.expectedFileSize(encoding, width, height);
    }

    /** Metres represented by a raw sample value. */
    public double toMeters(int rawValue) {
        return (double) rawValue * scaleNumerator / scaleDenominator;
    }

    /** Serialises the header into a fresh big-endian buffer of {@link TfDemFormat#HEADER_BYTES}. */
    public ByteBuffer toBuffer() {
        ByteBuffer buffer = ByteBuffer.allocate(TfDemFormat.HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(TfDemFormat.MAGIC);
        buffer.putShort((short) version);
        buffer.putShort((short) encoding);
        buffer.putInt(width);
        buffer.putInt(height);
        buffer.putInt(southLatitude);
        buffer.putInt(westLongitude);
        buffer.putInt(noDataRaw);
        buffer.putInt(scaleNumerator);
        buffer.putInt(scaleDenominator);
        while (buffer.hasRemaining()) {
            buffer.put((byte) 0);
        }
        return buffer.flip();
    }

    /**
     * Parses a header from the first {@link TfDemFormat#HEADER_BYTES} bytes of {@code buffer},
     * which is read absolutely and left untouched.
     *
     * @throws IOException when the bytes are not a readable {@code .tfdem} header
     */
    public static TfDemHeader parse(ByteBuffer buffer) throws IOException {
        if (buffer.limit() < TfDemFormat.HEADER_BYTES) {
            throw new IOException("Truncated .tfdem header: " + buffer.limit() + " bytes");
        }
        ByteBuffer big = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
        int magic = big.getInt(0);
        if (magic != TfDemFormat.MAGIC) {
            throw new IOException(String.format(Locale.ROOT,
                    "Not a .tfdem file: magic 0x%08X (expected 0x%08X)", magic, TfDemFormat.MAGIC));
        }
        try {
            return new TfDemHeader(
                    big.getShort(4) & 0xFFFF,
                    big.getShort(6) & 0xFFFF,
                    big.getInt(8),
                    big.getInt(12),
                    big.getInt(16),
                    big.getInt(20),
                    big.getInt(24),
                    big.getInt(28),
                    big.getInt(32));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid .tfdem header: " + e.getMessage(), e);
        }
    }
}
