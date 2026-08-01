package dev.terraforge.geo.landcover;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.cache.CacheStatistics;
import dev.terraforge.core.cache.ManagedCache;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.data.LandcoverProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@link LandcoverProvider} over a directory of prepared {@code .tflc} grids.
 *
 * <p>Land cover used to be loaded whole: every grid in the directory was read into the heap at
 * startup and every lookup scanned all of them. That is workable for one test region and impossible
 * for a planet -- a whole-Earth import is around 21,000 grids, tens of gigabytes of heap, and a
 * 21,000-element scan per block. Here the directory is catalogued from its headers instead, grids
 * are indexed by the degree cells they cover, and samples are read on demand into a cache bounded by
 * {@code cache.memory-limit-mb}. Startup cost is a few dozen bytes per file, and a lookup touches
 * only the grids that actually cover the point.
 *
 * <p>Where several grids cover a point -- a hand-prepared regional grid laid over a global import,
 * say -- the first one in filename order that has a class other than {@code UNKNOWN} wins, which is
 * the behaviour the eager loader had.
 */
public final class FileLandcoverProvider implements LandcoverProvider, AutoCloseable {

    private static final System.Logger LOG = System.getLogger("TerraForge-Landcover");

    /** Share of the cache budget land cover may occupy; DEM tiles are the larger consumer. */
    private static final double CACHE_BUDGET_FRACTION = 0.15;

    /**
     * A grid spanning more degree cells than this is indexed as "everywhere" rather than expanded.
     * It bounds the index for a pathological source -- one global 64 MiB grid -- without penalising
     * the ordinary case of one grid per degree cell.
     */
    private static final int MAX_INDEXED_CELLS = 4096;

    private final Path directory;
    private final Map<Long, List<Entry>> byCell;
    private final List<Entry> everywhere;
    private final ManagedCache<Path, Optional<GridLandcoverProvider>> grids;
    private final GeoBounds coverage;
    private final int gridCount;

    private FileLandcoverProvider(Path directory, Map<Long, List<Entry>> byCell, List<Entry> everywhere,
                                  ManagedCache<Path, Optional<GridLandcoverProvider>> grids,
                                  GeoBounds coverage, int gridCount) {
        this.directory = directory;
        this.byCell = byCell;
        this.everywhere = everywhere;
        this.grids = grids;
        this.coverage = coverage;
        this.gridCount = gridCount;
    }

    /** One catalogued grid: where it is, how big it is, and where to read it from. */
    private record Entry(Path file, LandcoverGridFile.Header header) {
    }

    /**
     * Catalogues {@code directory}.
     *
     * <p>A missing directory or an empty one is not an error: it yields a provider that reports
     * {@code UNKNOWN} everywhere, and the biome stage falls back to its climate estimate. A single
     * unreadable grid is reported and skipped rather than costing the server the other 20,999.
     *
     * @param maxResidentGrids {@code cache.landcover-grid-cache-entries}
     */
    public static FileLandcoverProvider open(Path directory, CacheManager cacheManager, int maxResidentGrids)
            throws IOException {
        if (maxResidentGrids <= 0) {
            throw new IllegalArgumentException(
                    "cache.landcover-grid-cache-entries must be positive: " + maxResidentGrids);
        }
        List<Entry> entries = catalogue(directory);
        long largestGridBytes = 0;
        for (Entry entry : entries) {
            largestGridBytes = Math.max(largestGridBytes, entry.header().sampleBytes());
        }

        Map<Long, List<Entry>> byCell = new HashMap<>();
        List<Entry> everywhere = new ArrayList<>();
        for (Entry entry : entries) {
            index(entry, byCell, everywhere);
        }
        byCell.replaceAll((cell, list) -> List.copyOf(list));

        long budgetBytes = (long) (cacheManager.memoryBudgetBytes() * CACHE_BUDGET_FRACTION);
        long maxWeightBytes = largestGridBytes > 0
                ? Math.min(budgetBytes, maxResidentGrids * largestGridBytes)
                : budgetBytes;
        ManagedCache<Path, Optional<GridLandcoverProvider>> grids = cacheManager.newWeighedCache(
                "landcover-grids", Math.max(1L, maxWeightBytes),
                (key, grid) -> (int) Math.min(Integer.MAX_VALUE,
                        grid.map(GridLandcoverProvider::sizeBytes).orElse(1L)));

        LOG.log(System.Logger.Level.INFO, "Land cover directory {0}: {1} prepared grid(s), {2} resident at most",
                directory, entries.size(), maxResidentGrids);
        return new FileLandcoverProvider(directory, Collections.unmodifiableMap(byCell),
                List.copyOf(everywhere), grids, coverageOf(entries), entries.size());
    }

    private static List<Entry> catalogue(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".tflc"))
                    // Filename order is the operator's tie-breaker between overlapping grids.
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        List<Entry> entries = new ArrayList<>(files.size());
        for (Path file : files) {
            try {
                entries.add(new Entry(file, LandcoverGridFile.readHeader(file)));
            } catch (IOException exception) {
                LOG.log(System.Logger.Level.WARNING, "Skipping unreadable land-cover grid {0}: {1}",
                        file.getFileName(), exception.getMessage());
            }
        }
        return entries;
    }

    /** Adds an entry to every degree cell it touches, or to the unindexed list when it spans too many. */
    private static void index(Entry entry, Map<Long, List<Entry>> byCell, List<Entry> everywhere) {
        LandcoverGridFile.Header header = entry.header();
        int southCell = (int) Math.floor(header.south());
        int westCell = (int) Math.floor(header.west());
        // A grid whose edge lands exactly on a degree line does not reach into the next cell.
        int northCell = (int) Math.ceil(header.north()) - 1;
        int eastCell = (int) Math.ceil(header.east()) - 1;
        long cells = (long) (northCell - southCell + 1) * (eastCell - westCell + 1);
        if (cells > MAX_INDEXED_CELLS) {
            everywhere.add(entry);
            return;
        }
        for (int lat = southCell; lat <= northCell; lat++) {
            for (int lon = westCell; lon <= eastCell; lon++) {
                byCell.computeIfAbsent(cellKey(lat, lon), key -> new ArrayList<>()).add(entry);
            }
        }
    }

    private static long cellKey(int latitudeDegree, int longitudeDegree) {
        return ((long) latitudeDegree << 32) ^ (longitudeDegree & 0xFFFFFFFFL);
    }

    private static GeoBounds coverageOf(List<Entry> entries) {
        if (entries.isEmpty()) {
            // Nothing prepared: claim nothing rather than the world, so /earth info reports the truth.
            return new GeoBounds(0.0, 0.0, 0.0, 0.0);
        }
        double south = Double.MAX_VALUE;
        double west = Double.MAX_VALUE;
        double north = -Double.MAX_VALUE;
        double east = -Double.MAX_VALUE;
        for (Entry entry : entries) {
            south = Math.min(south, entry.header().south());
            west = Math.min(west, entry.header().west());
            north = Math.max(north, entry.header().north());
            east = Math.max(east, entry.header().east());
        }
        return new GeoBounds(south, west, north, east);
    }

    @Override
    public LandcoverClass landcoverAt(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return LandcoverClass.UNKNOWN;
        }
        List<Entry> candidates = byCell.get(
                cellKey((int) Math.floor(latitude), (int) Math.floor(longitude)));
        LandcoverClass value = firstKnown(candidates, latitude, longitude);
        if (value != LandcoverClass.UNKNOWN) {
            return value;
        }
        return firstKnown(everywhere, latitude, longitude);
    }

    private LandcoverClass firstKnown(List<Entry> candidates, double latitude, double longitude) {
        if (candidates == null || candidates.isEmpty()) {
            return LandcoverClass.UNKNOWN;
        }
        for (Entry entry : candidates) {
            LandcoverClass value = grids.get(entry.file(), file -> load(entry))
                    .map(grid -> grid.landcoverAt(latitude, longitude))
                    .orElse(LandcoverClass.UNKNOWN);
            if (value != LandcoverClass.UNKNOWN) {
                return value;
            }
        }
        return LandcoverClass.UNKNOWN;
    }

    /**
     * A grid that catalogued cleanly but fails to read now -- deleted, truncated mid-run -- is cached
     * as absent. Retrying it on every block of the cell it covers would turn one broken file into a
     * permanent stall.
     */
    private Optional<GridLandcoverProvider> load(Entry entry) {
        try {
            return Optional.of(LandcoverGridFile.read(entry.file()));
        } catch (IOException exception) {
            LOG.log(System.Logger.Level.WARNING, "Cannot read land-cover grid {0}: {1}",
                    entry.file().getFileName(), exception.getMessage());
            return Optional.empty();
        } catch (RuntimeException exception) {
            throw new UncheckedIOException(new IOException("Invalid land-cover grid " + entry.file(), exception));
        }
    }

    public Path directory() {
        return directory;
    }

    /** Number of catalogued grids, whether or not they are resident. */
    public int gridCount() {
        return gridCount;
    }

    public GeoBounds coverage() {
        return coverage;
    }

    public CacheStatistics cacheStatistics() {
        return grids.statistics();
    }

    /** Drops cached grids; called on reload. The catalogue survives. */
    public void invalidate() {
        grids.invalidateAll();
    }

    @Override
    public void close() {
        grids.invalidateAll();
    }
}
