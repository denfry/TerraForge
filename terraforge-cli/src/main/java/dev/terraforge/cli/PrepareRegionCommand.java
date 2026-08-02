package dev.terraforge.cli;

import dev.terraforge.cli.dem.DemSource;
import dev.terraforge.cli.dem.DemTranscoder;
import dev.terraforge.cli.dem.BathymetryDemSource;
import dev.terraforge.cli.dem.GeoTiffDemFile;
import dev.terraforge.cli.dem.HgtDemSource;
import dev.terraforge.cli.dem.MergedDemSource;
import dev.terraforge.cli.dem.PreparedDemSource;
import dev.terraforge.cli.geo.BoundaryGeoJsonImporter;
import dev.terraforge.cli.geo.GeoNamesCityImporter;
import dev.terraforge.cli.geo.KarstGeoJsonImporter;
import dev.terraforge.cli.geo.KarstShapefileImporter;
import dev.terraforge.cli.geo.HydroRiversShapefileImporter;
import dev.terraforge.cli.geo.WaterGeoJsonImporter;
import dev.terraforge.cli.landcover.AsciiGridLandcoverImporter;
import dev.terraforge.cli.landcover.GeoTiffLandcoverImporter;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.geo.database.GeoDatabaseSchema;
import dev.terraforge.geo.dem.DemTileKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import dev.terraforge.cli.progress.ProgressReporter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.locationtech.jts.geom.Envelope;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Prepares every dataset for one bounding box in a single pass -- the usual way to set up the
 * central-europe test region.
 *
 * <p>This command owns no import logic of its own. It is the documented order of the individual
 * {@code prepare-*} commands run against one clip box, with one database transaction: boundaries
 * before cities, because a city resolves its country by ISO code as it is inserted.
 *
 * <p>The source tree is one directory per dataset. A missing directory means "no such data yet" and
 * is reported and skipped, not treated as an error -- a region with DEM but no gazetteer is a
 * perfectly normal intermediate state.
 *
 * <pre>
 *   input/
 *     dem/         *.hgt      -&gt; output/data/dem/*.tfdem
 *     landcover/   *.asc      -&gt; output/data/landcover/*.tflc
 *     boundaries/  *.geojson  -&gt; countries, regions
 *     cities/      *.txt      -&gt; cities
 *     water/       *.geojson  -&gt; water_bodies
 * </pre>
 */
@Command(name = "prepare-region",
        description = "Prepare DEM, landcover, water and geo data for one bounding box.")
public final class PrepareRegionCommand implements Callable<Integer> {

    private static final int EX_OK = 0;
    private static final int EX_USAGE = 64;
    private static final int EX_DATAERR = 65;
    private static final int EX_NOINPUT = 66;
    private static final int EX_IOERR = 74;

    /** Below this, a stage finishes before a progress line would have been printed. */
    private static final int PROGRESS_THRESHOLD = 20;

    /** Stages {@code --skip} accepts, in the order they run. */
    private static final List<String> STAGES = List.of("dem", "bathymetry", "landcover", "database");

    @Option(names = "--lat-min", required = true) double latMin;
    @Option(names = "--lat-max", required = true) double latMax;
    @Option(names = "--lon-min", required = true) double lonMin;
    @Option(names = "--lon-max", required = true) double lonMax;

    @Option(names = {"-i", "--input"}, required = true, description = "Directory with source datasets.")
    Path input;

    @Option(names = {"-o", "--output"}, required = true,
            description = "Plugin data directory, e.g. plugins/TerraForge")
    Path output;

    @Option(names = "--database", defaultValue = "terraforge.db",
            description = "Database file name, relative to --output. Default: ${DEFAULT-VALUE}")
    String databaseName;

    @Option(names = "--encoding", defaultValue = "int16",
            description = "DEM sample encoding: int16 (compact) or float32 (sub-metre / bathymetry).")
    String encoding;

    @Option(names = "--samples-per-degree", defaultValue = "600",
            description = "Land-cover cells per degree for GeoTIFF sources. Default: ${DEFAULT-VALUE}")
    int samplesPerDegree;

    @Option(names = "--blocks-per-km", defaultValue = "1.0",
            description = "Horizontal scale used to retain one-block-wide rivers. Default: ${DEFAULT-VALUE}")
    double blocksPerKm = 1.0;

    @Option(names = "--replace",
            description = "Replace prepared boundaries, cities and water, and rewrite existing tiles.")
    boolean replace;

    @Option(names = "--replace-database",
            description = "Replace the prepared vector tables without rewriting DEM or land-cover "
                    + "tiles. This is how a dataset is added to an already prepared region.")
    boolean replaceDatabase;

    @Option(names = "--skip", split = ",", paramLabel = "<stage>",
            description = "Stages not to run: dem, bathymetry, landcover, database.")
    List<String> skip;

    @Option(names = "--threads",
            description = "Raster files transcoded in parallel. Default: one per CPU core, at most 8.")
    Integer threads;

    @Override
    public Integer call() {
        Envelope clip;
        try {
            clip = clipEnvelope();
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return EX_USAGE;
        }
        if (!Files.isDirectory(input)) {
            System.err.println("Input directory does not exist: " + input);
            return EX_NOINPUT;
        }
        int demEncoding;
        int parallelism;
        try {
            validateStages();
            demEncoding = DemTranscoder.parseEncoding(encoding);
            parallelism = resolveThreads();
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return EX_USAGE;
        }

        List<Path> demFiles = sources("dem", ".hgt", ".tif", ".tiff");
        List<Path> bathymetryFiles = sources("bathymetry", ".tif", ".tiff");
        List<Path> landcoverFiles = sources("landcover", ".asc", ".tif", ".tiff");
        List<Path> boundaryFiles = sources("boundaries", ".geojson");
        List<Path> cityFiles = sources("cities", ".txt");
        List<Path> waterFiles = sources("water", ".geojson", ".shp");
        List<Path> karstFiles = sources("karst", ".geojson", ".shp");
        List<Path> entranceFiles = sources("cave-entrances", ".geojson");
        if (demFiles.isEmpty() && bathymetryFiles.isEmpty() && landcoverFiles.isEmpty() && boundaryFiles.isEmpty()
                && cityFiles.isEmpty() && waterFiles.isEmpty() && karstFiles.isEmpty() && entranceFiles.isEmpty()) {
            System.err.println("No source data found under " + input
                    + " -- expected dem/, bathymetry/, landcover/, boundaries/, cities/ or water/ subdirectories.");
            return EX_NOINPUT;
        }

        System.out.printf(Locale.ROOT, "Preparing region %.4f..%.4f N, %.4f..%.4f E%n",
                latMin, latMax, lonMin, lonMax);
        // Said before the work starts, because "27,000 DEM files on 8 threads" is what tells an
        // operator whether this is a coffee or an overnight run.
        System.out.printf(Locale.ROOT,
                "Sources:    %,d DEM, %,d bathymetry, %,d landcover, %,d boundary, %,d gazetteer, "
                        + "%,d water, %,d karst file(s); %d thread(s)%n",
                demFiles.size(), bathymetryFiles.size(), landcoverFiles.size(), boundaryFiles.size(), cityFiles.size(),
                waterFiles.size(), karstFiles.size(), parallelism);

        int failures = 0;
        if (skipping("dem")) {
            System.out.println("DEM:        skipped by --skip.");
        } else {
            failures += prepareDem(demFiles, clip, demEncoding);
        }
        if (skipping("bathymetry")) {
            System.out.println("Bathymetry: skipped by --skip.");
        } else {
            failures += prepareBathymetry(bathymetryFiles, clip, demEncoding);
        }
        if (skipping("landcover")) {
            System.out.println("Landcover:  skipped by --skip.");
        } else {
            failures += prepareLandcover(landcoverFiles, clip);
        }

        if (skipping("database")) {
            System.out.println("Database:   skipped by --skip.");
        } else if (!boundaryFiles.isEmpty() || !cityFiles.isEmpty() || !waterFiles.isEmpty() || !karstFiles.isEmpty() || !entranceFiles.isEmpty()) {
            int result = prepareDatabase(boundaryFiles, cityFiles, waterFiles, karstFiles, entranceFiles, clip);
            if (result != EX_OK) {
                return result;
            }
        }

        if (failures > 0) {
            System.err.println(failures + " source file(s) failed; the region is only partly prepared.");
            return EX_DATAERR;
        }
        System.out.println("Region prepared in " + output + ".");
        return EX_OK;
    }

    // --- stages -------------------------------------------------------------

    private int prepareDem(List<Path> files, Envelope clip, int demEncoding) {
        if (files.isEmpty()) {
            System.out.println("DEM:        no dem/ sources, skipped.");
            return 0;
        }
        Path target = output.resolve("data").resolve("dem");
        announce("DEM", files.size(), "source raster(s)");
        // Immutable, and every tile it writes goes to its own file, so one transcoder serves
        // every thread.
        DemTranscoder transcoder = new DemTranscoder(target, demEncoding, replace);
        AtomicInteger written = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger outside = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ProgressReporter progress = progressFor("Transcoded", files.size());

        forEach(files, file -> {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".hgt")) {
                if (!coversClip(file, clip)) {
                    outside.incrementAndGet();
                } else {
                    try (DemSource source = HgtDemSource.open(file)) {
                        (transcoder.transcode(source).skipped() ? skipped : written).incrementAndGet();
                    } catch (IOException | RuntimeException exception) {
                        failed.incrementAndGet();
                        fail(file, exception);
                    }
                }
                progress.step();
                return;
            }
            // A GeoTIFF covers an arbitrary extent, so the clip is applied per degree cell rather
            // than per file.
            try (GeoTiffDemFile raster = GeoTiffDemFile.open(file)) {
                for (DemTileKey key : raster.tiles()) {
                    if (!intersects(key, clip)) {
                        outside.incrementAndGet();
                        continue;
                    }
                    (transcoder.transcode(raster.sourceFor(key)).skipped() ? skipped : written)
                            .incrementAndGet();
                }
            } catch (IOException | RuntimeException exception) {
                failed.incrementAndGet();
                fail(file, exception);
            }
            progress.step();
        });

        progress.finish();
        System.out.println("DEM:        " + written + " tile(s) written, " + skipped + " already present, "
                + outside + " outside the box, " + failed + " failed -> " + target);
        return failed.get();
    }

    private int prepareBathymetry(List<Path> files, Envelope clip, int demEncoding) {
        if (files.isEmpty()) {
            System.out.println("Bathymetry: no bathymetry/ sources, skipped.");
            return 0;
        }
        Path target = output.resolve("data").resolve("dem");
        DemTranscoder transcoder = new DemTranscoder(target, demEncoding, true);
        AtomicInteger written = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        int cells = countBathymetryCells(files, clip);
        announce("Bathymetry", cells, "ocean cell(s) from " + files.size() + " raster(s)");
        ProgressReporter progress = progressFor("Transcoded", cells);
        forEach(files, file -> {
            try (GeoTiffDemFile raster = GeoTiffDemFile.open(file)) {
                for (DemTileKey key : raster.tiles()) {
                    if (!intersects(key, clip)) {
                        continue;
                    }
                    Path existing = target.resolve(key.fileName());
                    DemSource source = new BathymetryDemSource(raster.sourceFor(key));
                    if (Files.isRegularFile(existing)) {
                        try (PreparedDemSource land = PreparedDemSource.open(existing);
                             MergedDemSource merged = new MergedDemSource(land, source)) {
                            transcoder.transcode(merged);
                        }
                    } else {
                        try (source) {
                            transcoder.transcode(source);
                        }
                    }
                    written.incrementAndGet();
                    // Per cell, not per file: eight rasters cover the planet, so a file-level
                    // counter sits on 0/8 for the first hour and reports no usable rate or eta.
                    progress.step();
                }
            } catch (IOException | RuntimeException exception) {
                failed.incrementAndGet();
                fail(file, exception);
            }
        });
        progress.finish();
        System.out.println("Bathymetry: " + written + " tile(s) written, " + failed + " failed -> " + target);
        return failed.get();
    }

    /**
     * How many one-degree cells the bathymetry rasters actually contribute, for the progress total.
     *
     * <p>Metadata only -- {@link GeoTiffDemFile#tiles()} does not read samples -- so counting eight
     * rasters costs a moment against the hours the stage then runs.
     */
    private int countBathymetryCells(List<Path> files, Envelope clip) {
        int cells = 0;
        for (Path file : files) {
            try (GeoTiffDemFile raster = GeoTiffDemFile.open(file)) {
                for (DemTileKey key : raster.tiles()) {
                    if (intersects(key, clip)) {
                        cells++;
                    }
                }
            } catch (IOException | RuntimeException exception) {
                // Best effort: a raster that cannot be opened fails loudly in the transcode below,
                // which is where the failure belongs. An undercounted total only weakens the eta.
            }
        }
        return cells;
    }

    /**
     * A file whose name is not a valid tile key is never silently dropped: it is passed on to the
     * transcoder, which reports the real reason.
     */
    private static boolean coversClip(Path file, Envelope clip) {
        String name = file.getFileName().toString();
        DemTileKey key;
        try {
            key = DemTileKey.parse(name.substring(0, name.length() - ".hgt".length()));
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            return true;
        }
        return intersects(key, clip);
    }

    private static boolean intersects(DemTileKey key, Envelope clip) {
        var bounds = key.bounds();
        // Command bounds are [min, max): a cell beginning exactly at the north/east edge belongs
        // to the next request, not this one. JTS Envelope.intersects is closed on both ends.
        return bounds.minLongitude() < clip.getMaxX() && bounds.maxLongitude() > clip.getMinX()
                && bounds.minLatitude() < clip.getMaxY() && bounds.maxLatitude() > clip.getMinY();
    }

    private int prepareLandcover(List<Path> files, Envelope clip) {
        if (files.isEmpty()) {
            System.out.println("Landcover:  no landcover/ sources, skipped.");
            return 0;
        }
        Path target = output.resolve("data").resolve("landcover");
        GeoBounds bounds = new GeoBounds(clip.getMinY(), clip.getMinX(), clip.getMaxY(), clip.getMaxX());
        try {
            Files.createDirectories(target);
        } catch (IOException exception) {
            System.err.println("Cannot create " + target + ": " + exception.getMessage());
            return 1;
        }
        AtomicInteger written = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        announce("Landcover", files.size(), "source raster(s)");
        ProgressReporter progress = progressFor("Prepared", files.size());

        // Source tiles do not overlap, so no two threads write the same prepared cell.
        forEach(files, file -> {
            String name = file.getFileName().toString();
            try {
                if (name.toLowerCase(Locale.ROOT).endsWith(".asc")) {
                    Path grid = target.resolve(name.substring(0, name.length() - ".asc".length()) + ".tflc");
                    if (Files.exists(grid) && !replace) {
                        skipped.incrementAndGet();
                    } else {
                        AsciiGridLandcoverImporter.importFile(file, grid);
                        written.incrementAndGet();
                    }
                } else {
                    // A GeoTIFF covers several degree cells, so it produces one grid per cell.
                    var result = GeoTiffLandcoverImporter.importFile(file, target, samplesPerDegree,
                            bounds, replace);
                    written.addAndGet(result.written().size());
                    skipped.addAndGet(result.skipped().size());
                }
            } catch (IOException | RuntimeException exception) {
                failed.incrementAndGet();
                fail(file, exception);
            }
            progress.step();
        });

        progress.finish();
        System.out.println("Landcover:  " + written + " grid(s) written, " + skipped + " already present, "
                + failed + " failed -> " + target);
        return failed.get();
    }

    // --- parallelism --------------------------------------------------------

    /**
     * Transcoding is CPU-bound and every file is independent, so it scales with cores.
     *
     * <p>It is the difference between a usable planet-wide preparation and an unusable one: ~65,000
     * Copernicus tiles at a few seconds each is days on one thread. Files are independent -- each
     * writes tiles nothing else writes -- so the only shared state is the counters.
     */
    private void forEach(List<Path> files, java.util.function.Consumer<Path> action) {
        int parallelism = resolveThreads();
        if (parallelism <= 1 || files.size() <= 1) {
            files.forEach(action);
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(parallelism, files.size()),
                runnable -> {
                    Thread thread = new Thread(runnable, "terraforge-prepare");
                    thread.setDaemon(true);
                    // Below normal on purpose. This run saturates whatever it is given for hours;
                    // yielding to anything the operator is doing costs it almost nothing, because
                    // an idle machine still hands it every core.
                    thread.setPriority(Thread.NORM_PRIORITY - 2);
                    return thread;
                });
        try {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>(files.size());
            for (Path file : files) {
                futures.add(executor.submit(() -> action.accept(file)));
            }
            for (var future : futures) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException exception) {
                    // The action counts and reports its own failures; this is one that escaped.
                    throw new IllegalStateException("Preparation task failed", exception.getCause());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * One thread per core, less one.
     *
     * <p>The work is decompression -- a WorldCover tile is 36,000 square pixels that zlib must
     * inflate in full even to keep every twentieth -- so it scales with cores and nothing else.
     * Capping it at eight, as this once did, left most of a large machine idle for no reason.
     *
     * <p>The core left free is the point: a preparation run is hours long, and a machine pinned at
     * 100% for hours is a machine its owner cannot use. Combined with the reduced thread priority
     * in {@link #forEach}, the run takes the CPU it is given and yields it the moment anything else
     * wants it. Pass {@code --threads} to override in either direction.
     */
    private int resolveThreads() {
        if (threads != null) {
            if (threads < 1) {
                throw new IllegalArgumentException("--threads must be at least 1: " + threads);
            }
            return threads;
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    /** A misspelled stage silently preparing nothing is worse than a usage error. */
    private void validateStages() {
        if (skip == null) {
            return;
        }
        for (String stage : skip) {
            if (!STAGES.contains(stage.trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "Unknown stage '" + stage + "'; expected one of " + STAGES);
            }
        }
    }

    private boolean skipping(String stage) {
        return skip != null && skip.stream().anyMatch(value -> value.trim().equalsIgnoreCase(stage));
    }

    /**
     * Announces a stage before it works rather than after.
     *
     * <p>A stage that prints only its summary is indistinguishable from a hang for however long it
     * runs, which on a planet is hours.
     */
    private static void announce(String name, long items, String unit) {
        System.out.printf(Locale.ROOT, "%-11s %,d %s...%n", name + ":", items, unit);
        System.out.flush();
    }

    private static void fail(Path file, Exception exception) {
        synchronized (System.err) {
            System.err.println("  failed: " + file.getFileName() + ": " + exception.getMessage());
        }
    }

    /**
     * Progress for one stage, silent for the handful of files a small region has.
     *
     * <p>A planet-wide transcode is hours long and prints nothing per file, so without this it is
     * indistinguishable from a hang.
     */
    private static ProgressReporter progressFor(String label, int files) {
        return new ProgressReporter(System.out, label, files > PROGRESS_THRESHOLD ? files : 0, false);
    }

    /**
     * Imports every vector dataset in one transaction, so a failure half-way leaves the database
     * exactly as it was rather than half-populated.
     */
    private int prepareDatabase(List<Path> boundaries, List<Path> cities, List<Path> water, List<Path> karst, List<Path> entrances, Envelope clip) {
        Path database = output.resolve(databaseName).toAbsolutePath().normalize();
        try {
            Path parent = database.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
                GeoDatabaseSchema.install(connection);
                connection.setAutoCommit(false);
                try {
                    String occupied = clearOrDetect(connection, boundaries, cities, water, karst, entrances);
                    if (occupied != null) {
                        connection.rollback();
                        System.err.println(occupied + " already has data; pass --replace-database to "
                                + "rebuild the vector tables, or --replace to rebuild everything.");
                        return EX_DATAERR;
                    }
                    // Boundaries first: a city resolves its country by ISO code as it is inserted.
                    int countries = 0;
                    int unplaceable = 0;
                    for (Path file : boundaries) {
                        importing("boundaries", file);
                        var result = BoundaryGeoJsonImporter.importFile(file, connection, clip);
                        countries += result.imported();
                        unplaceable += result.skipped();
                    }
                    int places = 0;
                    for (Path file : cities) {
                        importing("cities", file);
                        places += GeoNamesCityImporter.importFile(file, connection, clip);
                    }
                    int waters = 0;
                    for (Path file : water) {
                        importing("water", file);
                        waters += file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".shp")
                                ? HydroRiversShapefileImporter.importFile(file, connection, clip, blocksPerKm).imported()
                                : WaterGeoJsonImporter.importFile(file, connection, clip, blocksPerKm).imported();
                    }
                    int karsts = 0;
                    for (Path file : karst) {
                        importing("karst", file);
                        karsts += file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".shp")
                                ? KarstShapefileImporter.importFile(file, connection, clip).imported()
                                : KarstGeoJsonImporter.importKarst(file, connection, clip);
                    }
                    int caveEntrances = 0;
                    for (Path file : entrances) {
                        importing("cave entrances", file);
                        caveEntrances += KarstGeoJsonImporter.importEntrances(file, connection, clip);
                    }
                    connection.commit();
                    report("Boundaries:", boundaries, countries, "feature(s)");
                    if (unplaceable > 0) {
                        // Almost always disputed territories, which Natural Earth publishes without
                        // an ISO code, and provinces whose country lies outside the box.
                        System.out.println("            " + unplaceable
                                + " feature(s) skipped: no ISO 3166-1 code, or no country in this region.");
                    }
                    report("Cities:    ", cities, places, "place(s)");
                    report("Water:     ", water, waters, "feature(s)");
                    report("Karst:     ", karst, karsts, "polygon(s)");
                    report("Entrances: ", entrances, caveEntrances, "OSM cave entrance(s)");
                    System.out.println("Database:   " + database);
                    return EX_OK;
                } catch (IOException | SQLException | RuntimeException exception) {
                    connection.rollback();
                    throw exception;
                }
            }
        } catch (IOException | SQLException | RuntimeException exception) {
            System.err.println("Cannot prepare " + database + ": " + exception.getMessage());
            return EX_IOERR;
        }
    }

    /** Empties the tables this run will write, or names the first one that already holds data. */
    private String clearOrDetect(Connection connection, List<Path> boundaries, List<Path> cities,
                                 List<Path> water, List<Path> karst, List<Path> entrances) throws SQLException {
        // regions before countries: a region row references a country row.
        List<String> targets = new java.util.ArrayList<>();
        if (!boundaries.isEmpty()) {
            targets.add("regions");
            targets.add("countries");
        }
        if (!cities.isEmpty()) {
            targets.add("cities");
        }
        if (!water.isEmpty()) {
            targets.add("water_bodies");
        }
        if (!karst.isEmpty()) targets.add("karst_areas");
        if (!entrances.isEmpty()) targets.add("cave_entrances");
        try (Statement statement = connection.createStatement()) {
            for (String table : targets) {
                if (replace || replaceDatabase) {
                    statement.executeUpdate("DELETE FROM " + table);
                } else {
                    try (var rows = statement.executeQuery("SELECT 1 FROM " + table + " LIMIT 1")) {
                        if (rows.next()) {
                            return table;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Names the vector file about to be read, before reading it.
     *
     * <p>The whole import is one transaction and the importers report only totals, so a 2 GB river
     * shapefile is otherwise several silent minutes between the land-cover summary and the first
     * database line.
     */
    private static void importing(String what, Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException exception) {
            size = -1;
        }
        System.out.printf(Locale.ROOT, "  reading %s: %s%s...%n", what, file.getFileName(),
                size < 0 ? "" : " (" + ProgressReporter.humanBytes(size) + ")");
        System.out.flush();
    }

    private static void report(String label, List<Path> files, int count, String unit) {
        if (files.isEmpty()) {
            System.out.println(label + " no sources, skipped.");
        } else {
            System.out.println(label + " " + count + " " + unit + " from " + files.size() + " file(s).");
        }
    }

    private List<Path> sources(String directory, String... extensions) {
        Path root = input.resolve(directory);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        for (String extension : extensions) {
                            if (name.endsWith(extension)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException exception) {
            System.err.println("Cannot scan " + root + ": " + exception.getMessage());
            return List.of();
        }
    }

    private Envelope clipEnvelope() {
        if (!Double.isFinite(latMin) || !Double.isFinite(latMax)
                || !Double.isFinite(lonMin) || !Double.isFinite(lonMax)
                || latMin < -90 || latMax > 90 || lonMin < -180 || lonMax > 180
                || latMin >= latMax || lonMin >= lonMax) {
            throw new IllegalArgumentException("The bounding box must satisfy "
                    + "-90 <= lat-min < lat-max <= 90 and -180 <= lon-min < lon-max <= 180");
        }
        return new Envelope(lonMin, lonMax, latMin, latMax);
    }
}
