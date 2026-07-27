package dev.terraforge.cli;

import dev.terraforge.core.config.ConfigLoader;
import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.coord.MinecraftPos;
import dev.terraforge.core.projection.ProjectionRegistry;
import dev.terraforge.geo.dem.DemTileKey;
import dev.terraforge.geo.dem.FileDemReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Plans and reports pregeneration work.
 *
 * <p>The CLI computes and validates the chunk set; the actual world writing is driven in-server by
 * {@code /earth pregenerate}, because only the server may write region files safely. Nothing here
 * writes to disk -- the command is a dry run by construction, which is what makes it safe to point
 * at a live server's data directory.
 *
 * <p>The most valuable output is the coverage check. A DEM gap found here costs a second; the same
 * gap found by a player who walks onto a flat plateau costs a regeneration.
 */
@Command(name = "pregenerate", description = "Plan chunk pregeneration for a world or region.")
public final class PregenerateCommand implements Callable<Integer> {

    private static final int EX_OK = 0;
    private static final int EX_USAGE = 64;
    private static final int EX_NOINPUT = 66;

    /** A region file holds 32 x 32 chunks. */
    private static final int CHUNKS_PER_REGION_AXIS = 32;

    /**
     * Rough bytes per generated chunk, from the measured sizes in docs/pregeneration.md (~40 MB for
     * ~2,800 chunks). Terrain-only worlds compress well; this is a deliberate over-estimate rather
     * than a promise.
     */
    private static final long BYTES_PER_CHUNK = 15L * 1024L;

    /** Upper bound on coverage probes per axis. A full scan of a continental plan would take minutes. */
    private static final int MAX_PROBES_PER_AXIS = 64;

    private static final int MISSING_TILES_SHOWN = 20;

    @Option(names = {"-c", "--config"}, defaultValue = "plugins/TerraForge/terraforge.yml",
            description = "terraforge.yml to plan against. Default: ${DEFAULT-VALUE}")
    Path config;

    @Option(names = {"-w", "--world"}, description = "World name; defaults to world.name from the config.")
    String world;

    @Option(names = {"-r", "--radius"}, description = "Radius in blocks around the origin.")
    Integer radius;

    @Option(names = "--bbox", split = ",", arity = "4",
            description = "Geographic area to plan instead of a radius: latMin,lonMin,latMax,lonMax")
    double[] bbox;

    @Option(names = "--test-region", description = "Plan the test-region bounds from the config.")
    boolean testRegion;

    @Option(names = "--data",
            description = "DEM tile directory; defaults to <config dir>/<data.data-directory>/dem")
    Path data;

    @Override
    public Integer call() {
        int modes = (radius == null ? 0 : 1) + (bbox == null ? 0 : 1) + (testRegion ? 1 : 0);
        if (modes != 1) {
            System.err.println("Choose exactly one of --radius, --bbox or --test-region.");
            return EX_USAGE;
        }
        if (radius != null && radius <= 0) {
            System.err.println("--radius must be positive.");
            return EX_USAGE;
        }
        if (!Files.isRegularFile(config)) {
            System.err.println("Configuration file does not exist: " + config);
            return EX_NOINPUT;
        }

        TerraForgeConfig loaded;
        try {
            ConfigLoader loader = new ConfigLoader();
            loaded = loader.validate(loader.load(config));
        } catch (IOException | RuntimeException exception) {
            System.err.println("Cannot read " + config + ": " + exception.getMessage());
            return EX_NOINPUT;
        }

        CoordinateTransformer transformer = new CoordinateTransformer(
                new ProjectionRegistry().create(loaded.earth().projection(), loaded.earth().origin().latitude()),
                loaded.earth().originPoint(), loaded.scale().blocksPerKm());

        Extent extent;
        try {
            extent = extent(loaded, transformer);
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return EX_USAGE;
        }

        String worldName = world == null ? loaded.world().name() : world;
        long chunks = extent.chunkCount();

        System.out.println("World:        " + worldName);
        System.out.println("Projection:   " + transformer.projection().description());
        System.out.printf(Locale.ROOT, "Scale:        %s blocks/km (%.0f m per block)%n",
                loaded.scale().blocksPerKm(), transformer.metersPerBlock());
        System.out.println("Origin:       " + transformer.origin());
        System.out.println();
        System.out.printf(Locale.ROOT, "Blocks:       X %,d..%,d, Z %,d..%,d%n",
                extent.minBlockX(), extent.maxBlockX(), extent.minBlockZ(), extent.maxBlockZ());
        System.out.printf(Locale.ROOT, "Chunks:       X %,d..%,d, Z %,d..%,d = %,d chunks%n",
                extent.minChunkX(), extent.maxChunkX(), extent.minChunkZ(), extent.maxChunkZ(), chunks);
        System.out.printf(Locale.ROOT, "Region files: %,d, roughly %s on disk%n",
                extent.regionCount(), humanBytes(chunks * BYTES_PER_CHUNK));
        System.out.println();

        reportCoverage(loaded, transformer, extent);

        System.out.println();
        System.out.println("Nothing was written. Run the generation in-server:");
        System.out.println("  " + inGameCommand(extent));
        return EX_OK;
    }

    // --- planning -----------------------------------------------------------

    private Extent extent(TerraForgeConfig loaded, CoordinateTransformer transformer) {
        if (radius != null) {
            return new Extent(-radius, -radius, radius, radius);
        }
        GeoBounds bounds = testRegion ? loaded.testRegion().toBounds() : bboxBounds();
        // Every supported projection is monotonic in both axes, so two opposite corners bound the
        // area exactly -- the interior needs no sampling.
        MinecraftPos northWest = transformer.toMinecraft(bounds.maxLatitude(), bounds.minLongitude());
        MinecraftPos southEast = transformer.toMinecraft(bounds.minLatitude(), bounds.maxLongitude());
        return new Extent(
                (int) Math.floor(Math.min(northWest.x(), southEast.x())),
                (int) Math.floor(Math.min(northWest.z(), southEast.z())),
                (int) Math.ceil(Math.max(northWest.x(), southEast.x())),
                (int) Math.ceil(Math.max(northWest.z(), southEast.z())));
    }

    private GeoBounds bboxBounds() {
        if (bbox.length != 4 || bbox[0] < -90 || bbox[2] > 90 || bbox[1] < -180 || bbox[3] > 180
                || bbox[0] >= bbox[2] || bbox[1] >= bbox[3]) {
            throw new IllegalArgumentException("--bbox must be latMin,lonMin,latMax,lonMax within WGS84 bounds");
        }
        return new GeoBounds(bbox[0], bbox[1], bbox[2], bbox[3]);
    }

    /**
     * Reports how much of the planned area has prepared elevation.
     *
     * <p>Coverage is probed per DEM tile rather than per chunk: a tile covers a whole degree, so a
     * bounded grid of probes finds every gap that matters without reading a single sample.
     */
    private void reportCoverage(TerraForgeConfig loaded, CoordinateTransformer transformer, Extent extent) {
        Path demDirectory = data != null ? data : defaultDemDirectory(loaded);
        if (!Files.isDirectory(demDirectory)) {
            System.out.println("DEM:          " + demDirectory + " does not exist -- the whole area would be "
                    + "generated flat at terrain.fallback-elevation.");
            return;
        }
        FileDemReader reader;
        try {
            reader = FileDemReader.open(demDirectory);
        } catch (IOException exception) {
            System.out.println("DEM:          cannot read " + demDirectory + ": " + exception.getMessage());
            return;
        }
        try (reader) {
            Set<DemTileKey> required = new LinkedHashSet<>();
            Set<DemTileKey> missing = new LinkedHashSet<>();
            int stepX = Math.max(1, (extent.maxBlockX() - extent.minBlockX()) / MAX_PROBES_PER_AXIS);
            int stepZ = Math.max(1, (extent.maxBlockZ() - extent.minBlockZ()) / MAX_PROBES_PER_AXIS);
            for (int x = extent.minBlockX(); x <= extent.maxBlockX(); x += stepX) {
                for (int z = extent.minBlockZ(); z <= extent.maxBlockZ(); z += stepZ) {
                    GeoPoint point = transformer.toGeographic(x, z);
                    DemTileKey key = DemTileKey.of(point.latitude(), point.longitude());
                    if (required.add(key) && !reader.exists(key)) {
                        missing.add(key);
                    }
                }
            }
            int covered = required.size() - missing.size();
            System.out.printf(Locale.ROOT, "DEM:          %d of %d tile(s) prepared (%.1f%% of the area)%n",
                    covered, required.size(), required.isEmpty() ? 100.0 : 100.0 * covered / required.size());
            if (!missing.isEmpty()) {
                System.out.println("Missing tiles -- these areas would be flat at terrain.fallback-elevation:");
                missing.stream().limit(MISSING_TILES_SHOWN).forEach(key -> System.out.println("  " + key.fileName()));
                if (missing.size() > MISSING_TILES_SHOWN) {
                    System.out.println("  ... and " + (missing.size() - MISSING_TILES_SHOWN) + " more");
                }
                System.out.println("Prepare them with 'terraforge prepare-region' before generating.");
            }
        }
    }

    private Path defaultDemDirectory(TerraForgeConfig loaded) {
        Path root = config.toAbsolutePath().normalize().getParent();
        Path relative = Path.of(loaded.data().dataDirectory()).resolve("dem");
        return root == null ? relative : root.resolve(relative);
    }

    private String inGameCommand(Extent extent) {
        if (extent.isCentredSquare()) {
            return "/earth pregenerate " + (extent.maxChunkX() - extent.minChunkX()) / 2;
        }
        return "/earth pregenerate <radius-chunks>   (stand near chunk "
                + (extent.minChunkX() + extent.maxChunkX()) / 2 + ", "
                + (extent.minChunkZ() + extent.maxChunkZ()) / 2 + ")";
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /** Inclusive block extent of the planned area. */
    private record Extent(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {

        int minChunkX() {
            return minBlockX >> 4;
        }

        int maxChunkX() {
            return maxBlockX >> 4;
        }

        int minChunkZ() {
            return minBlockZ >> 4;
        }

        int maxChunkZ() {
            return maxBlockZ >> 4;
        }

        long chunkCount() {
            return (long) (maxChunkX() - minChunkX() + 1) * (maxChunkZ() - minChunkZ() + 1);
        }

        long regionCount() {
            long x = Math.floorDiv(maxChunkX(), CHUNKS_PER_REGION_AXIS)
                    - Math.floorDiv(minChunkX(), CHUNKS_PER_REGION_AXIS) + 1;
            long z = Math.floorDiv(maxChunkZ(), CHUNKS_PER_REGION_AXIS)
                    - Math.floorDiv(minChunkZ(), CHUNKS_PER_REGION_AXIS) + 1;
            return x * z;
        }

        /** True when {@code /earth pregenerate <radius>} from the origin reproduces this area. */
        boolean isCentredSquare() {
            int width = maxChunkX() - minChunkX();
            return width == maxChunkZ() - minChunkZ()
                    && width % 2 == 0
                    && minChunkX() == -(width / 2)
                    && minChunkZ() == -(width / 2);
        }
    }
}
