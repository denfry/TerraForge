package dev.terraforge.geo.dem;

import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.data.ElevationProvider;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A {@code .tfdem} tile backed by a read-only memory mapping.
 *
 * <p>Loading a tile is a {@code mmap}, not a decode: 25 MB of samples cost address space and page
 * cache instead of heap, and the operating system evicts cold pages for us. Sampling uses absolute
 * buffer reads only, so any number of chunk worker threads may share one tile without locking.
 *
 * <p>The mapping is released when the tile becomes unreachable -- there is no portable unmap on
 * Java 21, which is precisely why tiles are immutable and cached rather than opened per query.
 */
public final class MappedDemTile implements DemTile {

    private final TfDemHeader header;
    private final ByteBuffer samples;
    private final GeoBounds bounds;
    private final double latitudeStep;
    private final double longitudeStep;

    private MappedDemTile(TfDemHeader header, ByteBuffer samples) {
        this.header = header;
        this.samples = samples;
        this.bounds = header.key().bounds();
        // Samples sit on the tile edges (SRTM style): 3601 samples span 1 degree inclusive.
        this.latitudeStep = 1.0 / (header.height() - 1);
        this.longitudeStep = 1.0 / (header.width() - 1);
    }

    /**
     * Maps a prepared tile file.
     *
     * @throws IOException when the file is not a valid {@code .tfdem} tile, or its length disagrees
     *                     with the grid declared in its header
     */
    public static MappedDemTile open(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < TfDemFormat.HEADER_BYTES) {
                throw new IOException("Truncated .tfdem file: " + file + " (" + size + " bytes)");
            }
            ByteBuffer mapped = channel
                    .map(FileChannel.MapMode.READ_ONLY, 0, size)
                    .order(ByteOrder.BIG_ENDIAN);
            TfDemHeader header = TfDemHeader.parse(mapped);
            if (size != header.expectedFileSize()) {
                throw new IOException("Tile " + file + " is " + size + " bytes, header declares "
                        + header.expectedFileSize() + " (" + header.width() + "x" + header.height() + ")");
            }
            ByteBuffer samples = mapped.slice(TfDemFormat.HEADER_BYTES,
                            (int) (size - TfDemFormat.HEADER_BYTES))
                    .order(ByteOrder.BIG_ENDIAN);
            return new MappedDemTile(header, samples);
        }
    }

    public TfDemHeader header() {
        return header;
    }

    @Override
    public DemTileKey key() {
        return header.key();
    }

    @Override
    public GeoBounds bounds() {
        return bounds;
    }

    @Override
    public int width() {
        return header.width();
    }

    @Override
    public int height() {
        return header.height();
    }

    @Override
    public double sample(int x, int y) {
        if (x < 0 || y < 0 || x >= header.width() || y >= header.height()) {
            throw new IndexOutOfBoundsException("Sample (" + x + "," + y + ") outside "
                    + header.width() + "x" + header.height() + " tile " + key());
        }
        int index = y * header.width() + x;
        return switch (header.encoding()) {
            case TfDemFormat.ENCODING_INT16 -> {
                short raw = samples.getShort(index * 2);
                yield raw == (short) header.noDataRaw() ? ElevationProvider.NO_DATA : header.toMeters(raw);
            }
            case TfDemFormat.ENCODING_FLOAT32 -> {
                // NaN is the float32 sentinel, and NO_DATA is NaN, so it needs no translation.
                float raw = samples.getFloat(index * 4);
                yield raw * header.scaleNumerator() / (double) header.scaleDenominator();
            }
            default -> throw new IllegalStateException("Unsupported encoding: " + header.encoding());
        };
    }

    @Override
    public double interpolate(double latitude, double longitude) {
        double gridX = clamp((longitude - bounds.minLongitude()) / longitudeStep, header.width() - 1);
        double gridY = clamp((bounds.maxLatitude() - latitude) / latitudeStep, header.height() - 1);

        int x0 = (int) Math.floor(gridX);
        int y0 = (int) Math.floor(gridY);
        int x1 = Math.min(x0 + 1, header.width() - 1);
        int y1 = Math.min(y0 + 1, header.height() - 1);
        double fx = gridX - x0;
        double fy = gridY - y0;

        // No-data neighbours are dropped from the weighting instead of dragging the result toward
        // the sentinel: a coastal sample next to a hole must stay at its own height.
        // Unrolled and allocation-free: this runs at least 256 times per chunk.
        double weighted = 0.0;
        double totalWeight = 0.0;

        double weight = (1 - fx) * (1 - fy);
        double value = weight == 0.0 ? ElevationProvider.NO_DATA : sample(x0, y0);
        if (!ElevationProvider.isNoData(value)) {
            weighted += value * weight;
            totalWeight += weight;
        }
        weight = fx * (1 - fy);
        value = weight == 0.0 ? ElevationProvider.NO_DATA : sample(x1, y0);
        if (!ElevationProvider.isNoData(value)) {
            weighted += value * weight;
            totalWeight += weight;
        }
        weight = (1 - fx) * fy;
        value = weight == 0.0 ? ElevationProvider.NO_DATA : sample(x0, y1);
        if (!ElevationProvider.isNoData(value)) {
            weighted += value * weight;
            totalWeight += weight;
        }
        weight = fx * fy;
        value = weight == 0.0 ? ElevationProvider.NO_DATA : sample(x1, y1);
        if (!ElevationProvider.isNoData(value)) {
            weighted += value * weight;
            totalWeight += weight;
        }

        return totalWeight == 0.0 ? ElevationProvider.NO_DATA : weighted / totalWeight;
    }

    private static double clamp(double value, int max) {
        return value < 0.0 ? 0.0 : Math.min(value, max);
    }

    @Override
    public long sizeBytes() {
        return header.expectedFileSize();
    }

    @Override
    public String toString() {
        return "MappedDemTile[" + key() + " " + header.width() + "x" + header.height()
                + " encoding=" + header.encoding() + "]";
    }
}
