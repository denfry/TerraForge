package dev.terraforge.geo.dem;

/**
 * On-disk layout of a {@code .tfdem} tile.
 *
 * <p>A deliberately boring format: a fixed header followed by a raw sample grid, so a tile can be
 * memory-mapped and sampled without decoding. Written by {@code terraforge prepare-dem}, read by
 * {@link DemReader}. Full documentation lives in docs/dem.md.
 *
 * <pre>
 * offset size  field
 * 0      4     magic 'T','F','D','M'
 * 4      2     format version (big endian)
 * 6      2     sample encoding (see below)
 * 8      4     grid width  (samples, big endian)
 * 12     4     grid height (samples, big endian)
 * 16     4     south edge latitude, integer degrees (big endian, signed)
 * 20     4     west edge longitude, integer degrees (big endian, signed)
 * 24     4     no-data sentinel, as raw sample value (big endian, signed)
 * 28     4     vertical unit scale numerator   (samples * num / den = metres)
 * 32     4     vertical unit scale denominator
 * 36     1     flags: bit 0 means the tile contains GEBCO bathymetry (v2+)
 * 37     27    reserved, zero filled
 * 64     ...   samples, row-major from the north-west corner
 * </pre>
 *
 * <p>Row-major from the north-west corner matches how raster sources store data, so preparation is
 * a straight copy and sampling needs no row flipping.
 */
public final class TfDemFormat {

    public static final int MAGIC = 0x5446_444D; // "TFDM"
    /** Version written by current preparation commands. */
    public static final int VERSION = 2;
    /** Version without explicit content flags; kept readable for existing prepared data. */
    public static final int LEGACY_VERSION = 1;
    public static final int HEADER_BYTES = 64;

    /** 16-bit signed metres: SRTM-compatible, one byte per sample cheaper than floats. */
    public static final int ENCODING_INT16 = 1;
    /** 32-bit float metres: used when the source has sub-metre precision. */
    public static final int ENCODING_FLOAT32 = 2;

    /** Sentinel written for missing samples in {@link #ENCODING_INT16} tiles. */
    public static final short INT16_NO_DATA = Short.MIN_VALUE;

    /** Header flag at offset 36: samples include GEBCO bathymetry. */
    public static final int FLAG_BATHYMETRY = 1;

    private TfDemFormat() {
    }

    public static int bytesPerSample(int encoding) {
        return switch (encoding) {
            case ENCODING_INT16 -> 2;
            case ENCODING_FLOAT32 -> 4;
            default -> throw new IllegalArgumentException("Unsupported .tfdem encoding: " + encoding);
        };
    }

    public static long expectedFileSize(int encoding, int width, int height) {
        return HEADER_BYTES + (long) bytesPerSample(encoding) * width * height;
    }
}
