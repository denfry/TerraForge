package dev.terraforge.cli.dem;

import dev.terraforge.geo.dem.TfDemFormat;
import dev.terraforge.geo.dem.TfDemHeader;
import dev.terraforge.geo.dem.TfDemWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Turns a {@link DemSource} into a {@code .tfdem} tile.
 *
 * <p>The whole of preparation's per-tile work lives here, so both {@code prepare-dem} and
 * {@code prepare-region} transcode identically -- a tile prepared by either command is
 * byte-for-byte the same.
 */
public final class DemTranscoder {

    private final Path outputDirectory;
    private final int encoding;
    private final boolean overwrite;

    /**
     * @param encoding  {@link TfDemFormat#ENCODING_INT16} or {@link TfDemFormat#ENCODING_FLOAT32};
     *                  a source that needs float32 is upgraded rather than silently truncated
     * @param overwrite rewrite tiles that already exist
     */
    public DemTranscoder(Path outputDirectory, int encoding, boolean overwrite) {
        TfDemFormat.bytesPerSample(encoding);
        this.outputDirectory = outputDirectory;
        this.encoding = encoding;
        this.overwrite = overwrite;
    }

    /** Parses the {@code --encoding} option. */
    public static int parseEncoding(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "int16" -> TfDemFormat.ENCODING_INT16;
            case "float32" -> TfDemFormat.ENCODING_FLOAT32;
            default -> throw new IllegalArgumentException(
                    "Unknown encoding '" + name + "': expected int16 or float32");
        };
    }

    public Result transcode(DemSource source) throws IOException {
        Path target = outputDirectory.resolve(source.key().fileName());
        if (!overwrite && Files.exists(target)) {
            return new Result(target, 0, 0, true);
        }

        int effectiveEncoding = source.needsFloat32() ? TfDemFormat.ENCODING_FLOAT32 : encoding;
        boolean bathymetry = source.containsBathymetry();
        TfDemHeader header = effectiveEncoding == TfDemFormat.ENCODING_FLOAT32
                ? TfDemHeader.float32(source.key(), source.width(), source.height(), bathymetry)
                : TfDemHeader.int16(source.key(), source.width(), source.height(), bathymetry);

        long voids = 0;
        try (TfDemWriter writer = TfDemWriter.create(outputDirectory, header)) {
            for (int y = 0; y < source.height(); y++) {
                double[] row = source.readRow(y);
                for (double meters : row) {
                    if (Double.isNaN(meters)) {
                        voids++;
                    }
                }
                writer.writeRow(row);
            }
        }
        return new Result(target, (long) source.width() * source.height(), voids, false);
    }

    /**
     * @param file    the written tile
     * @param samples samples written, 0 when the tile was skipped
     * @param voids   samples that carried no data
     * @param skipped true when the tile already existed and {@code --overwrite} was not given
     */
    public record Result(Path file, long samples, long voids, boolean skipped) {

        public double voidPercentage() {
            return samples == 0 ? 0.0 : 100.0 * voids / samples;
        }
    }
}
