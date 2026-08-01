package dev.terraforge.cli;

import dev.terraforge.cli.fetch.Fetcher;
import dev.terraforge.cli.fetch.SourceCatalog;
import dev.terraforge.core.coord.GeoBounds;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Downloads the source datasets one bounding box needs, into the tree {@code prepare-region} reads.
 *
 * <p>This is the step that used to be a page of documentation and five browser tabs. It changes
 * nothing about how TerraForge works: the server still reads only prepared local data, and the
 * downloaded files are the publishers' own, under the publishers' own licences, which the command
 * prints when it finishes.
 */
@Command(name = "fetch",
        description = "Download the open source datasets a bounding box needs.")
public final class FetchCommand implements Callable<Integer> {

    private static final int EX_OK = 0;
    private static final int EX_USAGE = 64;
    private static final int EX_DATAERR = 65;

    @Mixin RegionOptions region;

    @Option(names = {"-o", "--output"}, defaultValue = "./source-data",
            description = "Directory to fill with source data. Default: ${DEFAULT-VALUE}")
    Path output;

    @Option(names = "--dem-resolution", defaultValue = "90",
            description = "Copernicus DEM resolution: 30 or 90 metres. Default: ${DEFAULT-VALUE}. "
                    + "30 m is roughly four times the download and is wasted below ~10 blocks/km.")
    String demResolution;

    @Option(names = "--cities", defaultValue = "cities15000",
            description = "GeoNames gazetteer: cities500, cities1000, cities5000 or cities15000. "
                    + "Default: ${DEFAULT-VALUE}")
    String cities;

    @Option(names = "--skip", split = ",", paramLabel = "<dataset>",
            description = "Datasets not to fetch: dem, bathymetry, landcover, boundaries, cities, water.")
    List<String> skip;

    @Option(names = "--dry-run", description = "Report how much would be downloaded, and stop.")
    boolean dryRun;

    @Option(names = "--replace", description = "Re-download files that are already present.")
    boolean replace;

    @Option(names = "--parallel", defaultValue = "6",
            description = "Concurrent transfers, 1 to 16. Default: ${DEFAULT-VALUE}")
    int parallel = Fetcher.DEFAULT_PARALLELISM;

    @Override
    public Integer call() {
        Fetcher.Options options;
        try {
            options = options();
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return EX_USAGE;
        }
        Fetcher fetcher = new Fetcher(System.out, System.err);
        if (dryRun) {
            return fetcher.report(options, output);
        }
        Fetcher.Summary summary = fetcher.fetch(options, output);
        if (!summary.complete()) {
            System.err.println(summary.failed() + " file(s) failed; re-run to fetch only what is missing.");
            return EX_DATAERR;
        }
        System.out.println();
        GeoBounds bounds = options.bounds();
        System.out.println("Next: prepare the region with");
        System.out.println("  terraforge prepare-region --lat-min " + bounds.minLatitude()
                + " --lat-max " + bounds.maxLatitude() + " --lon-min " + bounds.minLongitude()
                + " --lon-max " + bounds.maxLongitude() + " -i " + output + " -o <plugins/TerraForge>");
        return EX_OK;
    }

    private Fetcher.Options options() {
        GeoBounds bounds = region.bounds();
        Set<String> datasets = Fetcher.datasets(skip);
        if (datasets.isEmpty()) {
            throw new IllegalArgumentException("--skip excludes every dataset; nothing to fetch");
        }
        return new Fetcher.Options(bounds, SourceCatalog.DemResolution.parse(demResolution),
                cities, datasets, replace, parallel);
    }
}
