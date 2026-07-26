package dev.terraforge.geo.dem;

import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.data.ElevationProvider;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A {@link DemTile} backed by a memory-mapped {@code .tfdem} file.
 *
 * <p>Mapping rather than reading means a resident tile costs address space and page cache instead of
 * heap, and the OS evicts cold pages for us. Sampling uses absolute buffer reads only, which do not
 * touch the buffer position, so several generation threads can sample one tile concurrently.
 */
public final class MappedDemTile implements DemTile {

    private final TfDemHeader header;
    private final GeoBounds bounds;
    private final ByteBuffer samples;
    private final double metresPerRaw;

    private MappedDemTile(TfDemHeader header, ByteBuffer samples) {
        this.header = header;
        this.bounds = header.key().bounds();
        this.samples = samples;
        this.metresPerRaw = header.metresPerRaw();
    }

    /** Maps a prepared tile read-only. The file may be deleted afterwards; the mapping survives. */
    public static MappedDemTile map(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < TfDemFormat.HEADER_BYTES) {
                throw new IOException("truncated .tfdem: " + file + " is only " + size + " bytes");
            }
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            mapped.order(ByteOrder.BIG_ENDIAN);
            TfDemHeader header = TfDemHeader.read(mapped);
            if (size != header.fileBytes()) {
                throw new IOException("size mismatch in " + file + ": header describes "
                        + header.fileBytes() + " bytes, file is " + size);
            }
            ByteBuffer body = mapped.slice(TfDemFormat.HEADER_BYTES, (int) header.sampleBytes())
                    .order(ByteOrder.BIG_ENDIAN);
            return new MappedDemTile(header, body);
        }
    }

    /** Wraps an already-materialised tile image; used by tests and by in-memory transcoding. */
    public static MappedDemTile wrap(TfDemHeader header, ByteBuffer samples) {
        if (samples.remaining() != header.sampleBytes()) {
            throw new IllegalArgumentException("sample buffer holds " + samples.remaining()
                    + " bytes, header describes " + header.sampleBytes());
        }
        return new MappedDemTile(header, samples.slice().order(ByteOrder.BIG_ENDIAN));
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
            return ElevationProvider.NO_DATA;
        }
        int index = y * header.width() + x;
        if (header.encoding() == TfDemFormat.ENCODING_INT16) {
            short raw = samples.getShort(index * 2);
            return raw == (short) header.noDataRaw() ? ElevationProvider.NO_DATA : raw * metresPerRaw;
        }
        float raw = samples.getFloat(index * 4);
        return Float.isNaN(raw) ? ElevationProvider.NO_DATA : raw * metresPerRaw;
    }

    @Override
    public double interpolate(double latitude, double longitude) {
        // Grid index space: (0,0) is the north-west corner, x grows east, y grows south.
        double gx = (longitude - bounds.minLongitude()) / header.longitudeStep();
        double gy = (bounds.maxLatitude() - latitude) / header.latitudeStep();
        if (gx < 0 || gy < 0 || gx > header.width() - 1 || gy > header.height() - 1) {
            return ElevationProvider.NO_DATA;
        }

        int x0 = (int) Math.floor(gx);
        int y0 = (int) Math.floor(gy);
        // A point exactly on the east/south edge would otherwise index one sample past the grid.
        if (x0 >= header.width() - 1) {
            x0 = header.width() - 2;
        }
        if (y0 >= header.height() - 1) {
            y0 = header.height() - 2;
        }
        double fx = gx - x0;
        double fy = gy - y0;

        double v00 = sample(x0, y0);
        double v10 = sample(x0 + 1, y0);
        double v01 = sample(x0, y0 + 1);
        double v11 = sample(x0 + 1, y0 + 1);

        // Missing neighbours are dropped from the weighted mean instead of poisoning it with NaN;
        // this keeps a single void sample from punching a hole in otherwise good terrain.
        double weighted = 0.0;
        double weight = 0.0;
        weighted = accumulate(weighted, v00, (1 - fx) * (1 - fy));
        weight = addWeight(weight, v00, (1 - fx) * (1 - fy));
        weighted = accumulate(weighted, v10, fx * (1 - fy));
        weight = addWeight(weight, v10, fx * (1 - fy));
        weighted = accumulate(weighted, v01, (1 - fx) * fy);
        weight = addWeight(weight, v01, (1 - fx) * fy);
        weighted = accumulate(weighted, v11, fx * fy);
        weight = addWeight(weight, v11, fx * fy);

        return weight == 0.0 ? ElevationProvider.NO_DATA : weighted / weight;
    }

    private static double accumulate(double sum, double value, double weight) {
        return ElevationProvider.isNoData(value) ? sum : sum + value * weight;
    }

    private static double addWeight(double sum, double value, double weight) {
        return ElevationProvider.isNoData(value) ? sum : sum + weight;
    }

    @Override
    public long sizeBytes() {
        return header.fileBytes();
    }

    @Override
    public String toString() {
        return "DemTile[" + header.key() + " " + width() + "x" + height() + "]";
    }
}
