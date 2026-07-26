package dev.terraforge.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Prepares every dataset for one bounding box in a single pass -- the usual way to set up the
 * central-europe test region.
 */
@Command(name = "prepare-region",
        description = "Prepare DEM, landcover, water and geo data for one bounding box.")
public final class PrepareRegionCommand implements Callable<Integer> {

    @Option(names = "--lat-min", required = true) double latMin;
    @Option(names = "--lat-max", required = true) double latMax;
    @Option(names = "--lon-min", required = true) double lonMin;
    @Option(names = "--lon-max", required = true) double lonMax;

    @Option(names = {"-i", "--input"}, required = true, description = "Directory with source datasets.")
    Path input;

    @Option(names = {"-o", "--output"}, required = true,
            description = "Plugin data directory, e.g. plugins/TerraForge")
    Path output;

    @Override
    public Integer call() {
        return NotYetImplemented.report("prepare-region", "Phase 14 (CLI)");
    }
}
