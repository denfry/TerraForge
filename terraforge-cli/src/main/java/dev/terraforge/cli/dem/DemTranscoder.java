package dev.terraforge.cli.dem;

import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.geo.dem.DemTileKey;
import dev.terraforge.geo.dem.TfDemHeader;
import dev.terraforge.geo.dem.TfDemWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Turns source rasters into the fixed 1x1 degree {@code .tfdem} grid.
 *
 * <p>Sources rarely line up with degree cells -- national datasets come in arbitrary sheets, and
 * Copernicus tiles are offset by half a pixel -- so every output sample is resampled from the
 * sources rather than copied. Resampling once here is what lets the server sample tiles with a
 * single bilinear lookup and no reprojection at all.
 */
public final class DemTranscoder {

    /** Upper bound on tile side, so a mislabelled source cannot ask for a 100 GB tile. */
    private static final int MAX_TILE_SIDE = 7201;

    private final Path outputDirectory;
    private final boolean overwrite;
    private final Integer forcedSide;
    private final Encoding encoding;
    private final Consumer<String> log;

    public enum Encoding {
        INT16, FLOAT32, AUTO;

        public static Encoding parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "int16" -> INT16;
                case "float32" -> FLOAT32;
                case "auto" -> AUTO;
                default -> throw new IllegalArgumentException(
                        "unknown encoding '" + value + "' (expected int16, float32 or auto)");
            };
        }
    }

    /** What one run produced, for the command's summary line. */
    public record Result(int tilesWritten, int tilesSkipped, int tilesEmpty, List<String> failures) {
        public boolean ok() {
            return failures.isEmpty();
        }
    }

    public DemTranscoder(Path outputDirectory, boolean overwrite, Integer forcedSide,
                         Encoding encoding, Consumer<String> log) {
        this.outputDirectory = outputDirectory;
        this.overwrite = overwrite;
        this.forcedSide = forcedSide;
        this.encoding = encoding;
        this.log = log;
    }

    public Result run(List<Path> sources) throws IOException {
        Map<DemTileKey, List<Path>> plan = plan(sources);
        log.accept("Planned " + plan.size() + " tile(s) from " + sources.size() + " source file(s).");

        int written = 0;
        int skipped = 0;
        int empty = 0;
        List<String> failures = new ArrayList<>();

        for (Map.Entry<DemTileKey, List<Path>> entry : plan.entrySet()) {
            DemTileKey key = entry.getKey();
            if (!overwrite && Files.isRegularFile(outputDirectory.resolve(key.fileName()))) {
                skipped++;
                continue;
            }
            try {
                if (writeTile(key, entry.getValue())) {
                    written++;
                } else {
                    empty++;
                    log.accept("  " + key + ": no data in coverage, not written");
                }
            } catch (IOException | RuntimeException e) {
                // One unreadable source must not abandon the other 800 tiles.
                failures.add(key + ": " + e.getMessage());
                log.accept("  " + key + ": FAILED -- " + e.getMessage());
            }
        }
        return new Result(written, skipped, empty, List.copyOf(failures));
    }

    /** Which degree cells each source touches. A source may feed several cells and vice versa. */
    private Map<DemTileKey, List<Path>> plan(List<Path> sources) throws IOException {
        Map<DemTileKey, List<Path>> plan = new LinkedHashMap<>();
        for (Path source : sources) {
            try (SourceRaster raster = SourceRasters.open(source)) {
                log.accept("Source: " + raster.describe());
                for (DemTileKey key : tilesCovered(raster.bounds())) {
                    plan.computeIfAbsent(key, k -> new ArrayList<>()).add(source);
                }
            } catch (IOException | RuntimeException e) {
                log.accept("Source: " + source.getFileName() + " SKIPPED -- " + e.getMessage());
            }
        }
        Map<DemTileKey, List<Path>> sorted = new LinkedHashMap<>();
        plan.keySet().stream()
                .sorted((a, b) -> a.latDegree() != b.latDegree()
                        ? Integer.compare(a.latDegree(), b.latDegree())
                        : Integer.compare(a.lonDegree(), b.lonDegree()))
                .forEach(key -> sorted.put(key, plan.get(key)));
        return sorted;
    }

    static Set<DemTileKey> tilesCovered(GeoBounds bounds) {
        Set<DemTileKey> keys = new LinkedHashSet<>();
        int minLat = (int) Math.floor(bounds.minLatitude());
        int maxLat = (int) Math.ceil(bounds.maxLatitude()) - 1;
        int minLon = (int) Math.floor(bounds.minLongitude());
        int maxLon = (int) Math.ceil(bounds.maxLongitude()) - 1;
        for (int lat = minLat; lat <= Math.max(maxLat, minLat); lat++) {
            for (int lon = minLon; lon <= Math.max(maxLon, minLon); lon++) {
                if (lat >= -90 && lat <= 89 && lon >= -180 && lon <= 179) {
                    keys.add(new DemTileKey(lat, lon));
                }
            }
        }
        return keys;
    }

    /** @return false when every sample came out no-data, so no file is written */
    private boolean writeTile(DemTileKey key, List<Path> sourceFiles) throws IOException {
        List<SourceRaster> rasters = new ArrayList<>(sourceFiles.size());
        try {
            for (Path source : sourceFiles) {
                rasters.add(SourceRasters.open(source));
            }
            int side = tileSide(rasters);
            boolean useFloat = switch (encoding) {
                case INT16 -> false;
                case FLOAT32 -> true;
                case AUTO -> rasters.stream().anyMatch(SourceRaster::needsFloatPrecision);
            };
            TfDemHeader header = useFloat
                    ? TfDemHeader.float32(key, side, side)
                    : TfDemHeader.int16(key, side, side);

            GeoBounds bounds = key.bounds();
            double step = 1.0 / (side - 1);
            boolean anyData = false;
            double[] row = new double[side];

            TfDemWriter writer = new TfDemWriter(outputDirectory, header);
            try {
                for (int y = 0; y < side; y++) {
                    double latitude = bounds.maxLatitude() - y * step;
                    for (int x = 0; x < side; x++) {
                        double longitude = bounds.minLongitude() + x * step;
                        row[x] = sample(rasters, latitude, longitude);
                        anyData |= !Double.isNaN(row[x]);
                    }
                    writer.writeRow(row);
                }
                if (!anyData) {
                    writer.abort();
                }
            } catch (IOException | RuntimeException e) {
                writer.abort();
                throw e;
            } finally {
                writer.close();
            }

            if (anyData) {
                log.accept(String.format(Locale.ROOT, "  %s: %dx%d %s -> %s",
                        key, side, side, useFloat ? "float32" : "int16", header.key().fileName()));
            }
            return anyData;
        } finally {
            for (SourceRaster raster : rasters) {
                try {
                    raster.close();
                } catch (IOException e) {
                    log.accept("  warning: could not close a source: " + e.getMessage());
                }
            }
        }
    }

    /** First source with real data wins; later sources fill each other's voids. */
    private static double sample(List<SourceRaster> rasters, double latitude, double longitude) {
        for (SourceRaster raster : rasters) {
            double value = raster.elevationAt(latitude, longitude);
            if (!Double.isNaN(value)) {
                return value;
            }
        }
        return Double.NaN;
    }

    /**
     * Output samples per side. Matching the finest source keeps preparation lossless; forcing a
     * coarser grid is the way to trade detail for disk when the play scale cannot show it anyway.
     */
    private int tileSide(List<SourceRaster> rasters) {
        if (forcedSide != null) {
            return clampSide(forcedSide);
        }
        double finest = rasters.stream()
                .mapToDouble(SourceRaster::degreesPerPixel)
                .filter(step -> step > 0)
                .min()
                .orElse(1.0 / 1200.0);
        return clampSide((int) Math.round(1.0 / finest) + 1);
    }

    private static int clampSide(int side) {
        return Math.min(Math.max(side, 2), MAX_TILE_SIDE);
    }
}
