package dev.terraforge.cli;

import dev.terraforge.cli.dem.DemSource;
import dev.terraforge.cli.dem.DemTranscoder;
import dev.terraforge.cli.dem.GeoTiffDemFile;
import dev.terraforge.cli.dem.HgtDemSource;
import dev.terraforge.cli.geo.BoundaryGeoJsonImporter;
import dev.terraforge.cli.geo.GeoNamesCityImporter;
import dev.terraforge.cli.geo.WaterGeoJsonImporter;
import dev.terraforge.cli.landcover.AsciiGridLandcoverImporter;
import dev.terraforge.geo.database.GeoDatabaseSchema;
import dev.terraforge.geo.dem.DemTileKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
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

    @Option(names = "--replace",
            description = "Replace prepared boundaries, cities and water, and rewrite existing tiles.")
    boolean replace;

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
        try {
            demEncoding = DemTranscoder.parseEncoding(encoding);
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return EX_USAGE;
        }

        List<Path> demFiles = sources("dem", ".hgt", ".tif", ".tiff");
        List<Path> landcoverFiles = sources("landcover", ".asc");
        List<Path> boundaryFiles = sources("boundaries", ".geojson");
        List<Path> cityFiles = sources("cities", ".txt");
        List<Path> waterFiles = sources("water", ".geojson");
        if (demFiles.isEmpty() && landcoverFiles.isEmpty() && boundaryFiles.isEmpty()
                && cityFiles.isEmpty() && waterFiles.isEmpty()) {
            System.err.println("No source data found under " + input
                    + " -- expected dem/, landcover/, boundaries/, cities/ or water/ subdirectories.");
            return EX_NOINPUT;
        }

        System.out.printf(Locale.ROOT, "Preparing region %.4f..%.4f N, %.4f..%.4f E%n",
                latMin, latMax, lonMin, lonMax);

        int failures = prepareDem(demFiles, clip, demEncoding) + prepareLandcover(landcoverFiles);

        if (!boundaryFiles.isEmpty() || !cityFiles.isEmpty() || !waterFiles.isEmpty()) {
            int result = prepareDatabase(boundaryFiles, cityFiles, waterFiles, clip);
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
        DemTranscoder transcoder = new DemTranscoder(target, demEncoding, replace);
        int written = 0;
        int skipped = 0;
        int outside = 0;
        int failed = 0;
        for (Path file : files) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".hgt")) {
                if (!coversClip(file, clip)) {
                    outside++;
                    continue;
                }
                try (DemSource source = HgtDemSource.open(file)) {
                    if (transcoder.transcode(source).skipped()) {
                        skipped++;
                    } else {
                        written++;
                    }
                } catch (IOException | RuntimeException exception) {
                    failed++;
                    System.err.println("  failed: " + file.getFileName() + ": " + exception.getMessage());
                }
                continue;
            }
            // A GeoTIFF covers an arbitrary extent, so the clip is applied per degree cell rather
            // than per file.
            try (GeoTiffDemFile raster = GeoTiffDemFile.open(file)) {
                for (DemTileKey key : raster.tiles()) {
                    if (!intersects(key, clip)) {
                        outside++;
                        continue;
                    }
                    if (transcoder.transcode(raster.sourceFor(key)).skipped()) {
                        skipped++;
                    } else {
                        written++;
                    }
                }
            } catch (IOException | RuntimeException exception) {
                failed++;
                System.err.println("  failed: " + file.getFileName() + ": " + exception.getMessage());
            }
        }
        System.out.println("DEM:        " + written + " tile(s) written, " + skipped + " already present, "
                + outside + " outside the box, " + failed + " failed -> " + target);
        return failed;
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
        return clip.intersects(new Envelope(bounds.minLongitude(), bounds.maxLongitude(),
                bounds.minLatitude(), bounds.maxLatitude()));
    }

    private int prepareLandcover(List<Path> files) {
        if (files.isEmpty()) {
            System.out.println("Landcover:  no landcover/ sources, skipped.");
            return 0;
        }
        Path target = output.resolve("data").resolve("landcover");
        int written = 0;
        int skipped = 0;
        int failed = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            Path grid = target.resolve(name.substring(0, name.length() - ".asc".length()) + ".tflc");
            if (Files.exists(grid) && !replace) {
                skipped++;
                continue;
            }
            try {
                Files.createDirectories(target);
                AsciiGridLandcoverImporter.importFile(file, grid);
                written++;
            } catch (IOException | RuntimeException exception) {
                failed++;
                System.err.println("  failed: " + name + ": " + exception.getMessage());
            }
        }
        System.out.println("Landcover:  " + written + " grid(s) written, " + skipped + " already present, "
                + failed + " failed -> " + target);
        return failed;
    }

    /**
     * Imports every vector dataset in one transaction, so a failure half-way leaves the database
     * exactly as it was rather than half-populated.
     */
    private int prepareDatabase(List<Path> boundaries, List<Path> cities, List<Path> water, Envelope clip) {
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
                    String occupied = clearOrDetect(connection, boundaries, cities, water);
                    if (occupied != null) {
                        connection.rollback();
                        System.err.println(occupied + " already has data; pass --replace to replace it.");
                        return EX_DATAERR;
                    }
                    // Boundaries first: a city resolves its country by ISO code as it is inserted.
                    int countries = 0;
                    for (Path file : boundaries) {
                        countries += BoundaryGeoJsonImporter.importFile(file, connection, clip);
                    }
                    int places = 0;
                    for (Path file : cities) {
                        places += GeoNamesCityImporter.importFile(file, connection, clip);
                    }
                    int waters = 0;
                    for (Path file : water) {
                        waters += WaterGeoJsonImporter.importFile(file, connection, clip);
                    }
                    connection.commit();
                    report("Boundaries:", boundaries, countries, "feature(s)");
                    report("Cities:    ", cities, places, "place(s)");
                    report("Water:     ", water, waters, "feature(s)");
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
                                 List<Path> water) throws SQLException {
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
        try (Statement statement = connection.createStatement()) {
            for (String table : targets) {
                if (replace) {
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
