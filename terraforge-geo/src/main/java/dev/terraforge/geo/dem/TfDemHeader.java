package dev.terraforge.geo.dem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The 64-byte header at the start of every {@code .tfdem} file.
 *
 * <p>Field layout is documented on {@link TfDemFormat}. Everything is big endian and fixed width, so
 * the header can be read with a single {@code ByteBuffer} and never needs a parser.
 *
 * @param encoding   {@link TfDemFormat#ENCODING_INT16} or {@link TfDemFormat#ENCODING_FLOAT32}
 * @param width      samples along the longitude axis
 * @param height     samples along the latitude axis
 * @param latDegree  southern edge, integer degrees
 * @param lonDegree  western edge, integer degrees
 * @param noDataRaw  the sentinel as a raw sample value: the {@code short} for int16 tiles, the
 *                   IEEE-754 bit pattern for float32 tiles
 * @param scaleNum   raw sample * {@code scaleNum / scaleDen} = metres
 * @param scaleDen   never zero
 */
public record TfDemHeader(
        int encoding,
        int width,
        int height,
        int latDegree,
        int lonDegree,
        int noDataRaw,
        int scaleNum,
        int scaleDen) {

    public TfDemHeader {
        TfDemFormat.bytesPerSample(encoding); // rejects unknown encodings
        if (width <= 1 || height <= 1) {
            throw new IllegalArgumentException("a tile needs at least 2x2 samples, got " + width + "x" + height);
        }
        if (latDegree < -90 || latDegree > 89) {
            throw new IllegalArgumentException("latDegree out of range: " + latDegree);
        }
        if (lonDegree < -180 || lonDegree > 179) {
            throw new IllegalArgumentException("lonDegree out of range: " + lonDegree);
        }
        if (scaleDen == 0) {
            throw new IllegalArgumentException("vertical scale denominator must not be zero");
        }
    }

    /** An int16 tile in whole metres -- what {@code prepare-dem} writes by default. */
    public static TfDemHeader int16(DemTileKey key, int width, int height) {
        return new TfDemHeader(TfDemFormat.ENCODING_INT16, width, height,
                key.latDegree(), key.lonDegree(), TfDemFormat.INT16_NO_DATA, 1, 1);
    }

    /** A float32 tile in metres, for sub-metre sources and bathymetry. */
    public static TfDemHeader float32(DemTileKey key, int width, int height) {
        return new TfDemHeader(TfDemFormat.ENCODING_FLOAT32, width, height,
                key.latDegree(), key.lonDegree(), Float.floatToRawIntBits(Float.NaN), 1, 1);
    }

    public DemTileKey key() {
        return new DemTileKey(latDegree, lonDegree);
    }

    public long sampleBytes() {
        return (long) TfDemFormat.bytesPerSample(encoding) * width * height;
    }

    public long fileBytes() {
        return TfDemFormat.HEADER_BYTES + sampleBytes();
    }

    /** Degrees between adjacent samples. The grid is pixel-is-point: both edges carry a sample. */
    public double longitudeStep() {
        return 1.0 / (width - 1);
    }

    public double latitudeStep() {
        return 1.0 / (height - 1);
    }

    public double metresPerRaw() {
        return (double) scaleNum / scaleDen;
    }

    public ByteBuffer toBuffer() {
        ByteBuffer buffer = ByteBuffer.allocate(TfDemFormat.HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(TfDemFormat.MAGIC);
        buffer.putShort((short) TfDemFormat.VERSION);
        buffer.putShort((short) encoding);
        buffer.putInt(width);
        buffer.putInt(height);
        buffer.putInt(latDegree);
        buffer.putInt(lonDegree);
        buffer.putInt(noDataRaw);
        buffer.putInt(scaleNum);
        buffer.putInt(scaleDen);
        // remaining 28 reserved bytes stay zero
        return buffer.flip();
    }

    /**
     * Reads a header from a buffer positioned at the start of the file.
     *
     * @throws IOException when the file is not a {@code .tfdem}, or is a version this build cannot
     *                     read -- a corrupt tile must fail loudly, not generate wrong terrain
     */
    public static TfDemHeader read(ByteBuffer buffer) throws IOException {
        if (buffer.remaining() < TfDemFormat.HEADER_BYTES) {
            throw new IOException("truncated .tfdem: " + buffer.remaining() + " bytes, need at least "
                    + TfDemFormat.HEADER_BYTES);
        }
        ByteBuffer view = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
        int magic = view.getInt();
        if (magic != TfDemFormat.MAGIC) {
            throw new IOException(String.format("not a .tfdem file (magic 0x%08X)", magic));
        }
        int version = Short.toUnsignedInt(view.getShort());
        if (version != TfDemFormat.VERSION) {
            throw new IOException("unsupported .tfdem version " + version
                    + "; this build reads version " + TfDemFormat.VERSION + ". Re-run prepare-dem.");
        }
        int encoding = Short.toUnsignedInt(view.getShort());
        int width = view.getInt();
        int height = view.getInt();
        int latDegree = view.getInt();
        int lonDegree = view.getInt();
        int noDataRaw = view.getInt();
        int scaleNum = view.getInt();
        int scaleDen = view.getInt();
        try {
            return new TfDemHeader(encoding, width, height, latDegree, lonDegree, noDataRaw, scaleNum, scaleDen);
        } catch (IllegalArgumentException e) {
            throw new IOException("corrupt .tfdem header: " + e.getMessage(), e);
        }
    }
}
