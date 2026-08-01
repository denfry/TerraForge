package dev.terraforge.cli;

import dev.terraforge.cli.fetch.Fetcher;
import dev.terraforge.cli.fetch.SourceCatalog;
import dev.terraforge.cli.setup.PluginLayout;
import dev.terraforge.cli.setup.VerticalProfile;
import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.GeoPoint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Everything a bounding box needs to become a generating world, in one command.
 *
 * <p>It is the four existing steps in their only sensible order -- configure, download, prepare,
 * validate -- run against a single bounding box so they cannot disagree about which region is being
 * built. Each step remains available on its own; this one exists because the failure mode of doing
 * them by hand is silent, not loud: a region prepared for one box and configured for another
 * generates a world of fallback terrain and nothing reports an error.
 *
 * <p>The steps degrade rather than abort. A tile the publisher does not have, a dataset skipped, a
 * mirror that fails once -- each leaves the rest of the region prepared, and the summary says what
 * is missing. Re-running fills only the gaps, because the source directory is a cache.
 */
@Command(name = "setup",
        description = "Fetch, prepare, configure and validate one region end to end.")
public final class SetupCommand implements Callable<Integer> {

    private static final int EX_OK = 0;
    private static final int EX_USAGE = 64;
    private static final int EX_DATAERR = 65;

    @Mixin RegionOptions region;

    @Option(names = {"-o", "--output"}, required = true,
            description = "Plugin data directory, e.g. server/plugins/TerraForge")
    Path output;

    @Option(names = {"-i", "--source-data"}, defaultValue = "./source-data",
            description = "Directory the downloaded sources are cached in. Default: ${DEFAULT-VALUE}")
    Path sourceData;

    @Option(names = "--world", defaultValue = "earth",
            description = "Name of the world this configuration generates. Default: ${DEFAULT-VALUE}")
    String world;

    @Option(names = "--scale", defaultValue = "1.0",
            description = "Blocks per kilometre of ground. Default: ${DEFAULT-VALUE}")
    double scale;

    @Option(names = "--origin-lat",
            description = "Latitude mapped to block 0. Default: the centre of the bounding box.")
    Double originLatitude;

    @Option(names = "--origin-lon",
            description = "Longitude mapped to block 0. Default: the centre of the bounding box.")
    Double originLongitude;

    @Option(names = "--region-name", defaultValue = "prepared-region",
            description = "Label for the region in /earth info. Default: ${DEFAULT-VALUE}")
    String regionName;

    @Option(names = "--dem-resolution", defaultValue = "90",
            description = "Copernicus DEM resolution: 30 or 90 metres. Default: ${DEFAULT-VALUE}")
    String demResolution;

    @Option(names = "--cities", defaultValue = "cities15000",
            description = "GeoNames gazetteer to import. Default: ${DEFAULT-VALUE}")
    String cities;

    @Option(names = "--skip", split = ",", paramLabel = "<dataset>",
            description = "Datasets not to fetch: dem, landcover, boundaries, cities, water.")
    List<String> skip;

    @Option(names = "--encoding", defaultValue = "int16",
            description = "DEM sample encoding: int16 (compact) or float32 (sub-metre / bathymetry).")
    String encoding;

    @Option(names = "--samples-per-degree", defaultValue = "600",
            description = "Land-cover cells per degree. Default: ${DEFAULT-VALUE}")
    int samplesPerDegree;

    @Option(names = "--offline",
            description = "Skip downloading and prepare whatever is already in --source-data.")
    boolean offline;

    @Option(names = "--dry-run", description = "Report how much would be downloaded, and stop.")
    boolean dryRun;

    @Option(names = "--replace",
            description = "Re-download sources and rewrite prepared tiles and tables.")
    boolean replace;

    @Option(names = "--replace-config", description = "Overwrite an existing terraforge.yml.")
    boolean replaceConfig;

    // Initialised as well as declared: picocli applies defaultValue, callers that build the command
    // directly (setup does exactly that for prepare-region) do not.
    @Option(names = "--parallel", defaultValue = "6",
            description = "Concurrent downloads, 1 to 16. Default: ${DEFAULT-VALUE}")
    int parallel = Fetcher.DEFAULT_PARALLELISM;

    @Option(names = "--threads",
            description = "Raster files transcoded in parallel. Default: one per CPU core, at most 8.")
    Integer threads;

    @Option(names = "--meters-per-block",
            description = "Vertical metres in one block. Default: 1 for a region, 20 for --whole-world. "
                    + "Lower is more dramatic and needs a taller world.")
    Double metersPerBlock;

    @Option(names = "--min-y", description = "World floor. Needs a matching dimension type datapack, "
            + "which this command writes. Minecraft allows -2032 and up.")
    Integer minY;

    @Option(names = "--max-y", description = "World ceiling. Minecraft allows up to 2032.")
    Integer maxY;

    @Option(names = "--sea-level", description = "Y of sea level. Default: 63 for a region, 0 for a planet.")
    Integer seaLevel;

    @Override
    public Integer call() {
        GeoBounds bounds;
        Fetcher.Options fetchOptions;
        PluginLayout.Options layoutOptions;
        try {
            bounds = region.bounds();
            layoutOptions = layoutOptions(bounds);
            fetchOptions = fetchOptions(bounds);
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return EX_USAGE;
        }

        Fetcher fetcher = new Fetcher(System.out, System.err);
        if (dryRun) {
            return fetcher.report(fetchOptions, sourceData);
        }

        System.out.println("== 1/4  Plugin directory ==");
        PluginLayout layout = new PluginLayout(System.out);
        TerraForgeConfig config;
        try {
            config = layout.create(output, layoutOptions);
        } catch (Exception exception) {
            System.err.println("Cannot initialise " + output + ": " + exception.getMessage());
            return 74;
        }

        int incomplete = 0;
        System.out.println();
        System.out.println("== 2/4  Source data ==");
        if (offline) {
            System.out.println("Offline: using whatever is already in " + sourceData + ".");
        } else {
            Fetcher.Summary summary = fetcher.fetch(fetchOptions, sourceData);
            incomplete += summary.complete() ? 0 : 1;
        }

        System.out.println();
        System.out.println("== 3/4  Prepare ==");
        int prepared = prepare(bounds);
        if (prepared != EX_OK) {
            incomplete++;
        }

        System.out.println();
        System.out.println("== 4/4  Validate ==");
        validate(config);

        layout.printWorldInstructions(config, output, layoutOptions.vertical());
        if (incomplete > 0) {
            System.err.println();
            System.err.println("The region is only partly prepared -- see the messages above. "
                    + "Re-running setup fetches and prepares only what is missing.");
            return EX_DATAERR;
        }
        return EX_OK;
    }

    /** Runs {@code prepare-region} in-process against the same box and directories. */
    private int prepare(GeoBounds bounds) {
        PrepareRegionCommand prepare = new PrepareRegionCommand();
        prepare.latMin = bounds.minLatitude();
        prepare.latMax = bounds.maxLatitude();
        prepare.lonMin = bounds.minLongitude();
        prepare.lonMax = bounds.maxLongitude();
        prepare.input = sourceData;
        prepare.output = output;
        prepare.databaseName = "terraforge.db";
        prepare.encoding = encoding;
        prepare.samplesPerDegree = samplesPerDegree;
        prepare.blocksPerKm = scale;
        prepare.threads = threads;
        // Prepared data is derived data: on a --replace run it is rebuilt from the sources rather
        // than kept, so the world always matches what was fetched.
        prepare.replace = replace;
        return prepare.call();
    }

    /**
     * Runs the data-quality checks, for information only.
     *
     * <p>Its findings are about the source datasets, not about whether the world will generate: a
     * country with no capital is worth knowing and is no reason to fail a setup.
     */
    private void validate(TerraForgeConfig config) {
        Path database = output.resolve(config.data().databaseFile());
        if (!Files.isRegularFile(database)) {
            System.out.println("No prepared database to validate.");
            return;
        }
        ValidateCommand validate = new ValidateCommand();
        validate.database = database;
        validate.strict = false;
        validate.call();
    }

    private PluginLayout.Options layoutOptions(GeoBounds bounds) {
        if ((originLatitude == null) != (originLongitude == null)) {
            throw new IllegalArgumentException("--origin-lat and --origin-lon must be given together");
        }
        if (!Double.isFinite(scale) || scale <= 0.0) {
            throw new IllegalArgumentException("--scale must be greater than 0");
        }
        GeoPoint origin = originLatitude == null ? null : new GeoPoint(originLatitude, originLongitude);
        return new PluginLayout.Options(bounds, world, scale, origin, regionName, replaceConfig,
                verticalProfile(bounds));
    }

    /** Explicit vertical settings override the profile the bounding box implies, field by field. */
    private VerticalProfile verticalProfile(GeoBounds bounds) {
        VerticalProfile base = VerticalProfile.forBounds(bounds);
        if (metersPerBlock == null && minY == null && maxY == null && seaLevel == null) {
            return base;
        }
        return new VerticalProfile(
                seaLevel == null ? base.seaLevel() : seaLevel,
                minY == null ? base.minY() : minY,
                maxY == null ? base.maxY() : maxY,
                metersPerBlock == null ? base.metersPerBlock() : metersPerBlock);
    }

    private Fetcher.Options fetchOptions(GeoBounds bounds) {
        Set<String> datasets = Fetcher.datasets(skip);
        return new Fetcher.Options(bounds, SourceCatalog.DemResolution.parse(demResolution),
                cities, datasets, replace, parallel);
    }
}
