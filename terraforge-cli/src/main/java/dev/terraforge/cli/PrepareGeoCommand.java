package dev.terraforge.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Imports boundaries, gazetteer entries and natural water polygons into {@code terraforge.db}.
 *
 * <p>Only natural and administrative features are accepted. Roads, buildings, railways and other
 * man-made geometry are rejected at import time, so they cannot reach the world even by accident.
 */
@Command(name = "prepare-geo", description = "Import country, region, city and natural water data.")
public final class PrepareGeoCommand implements Callable<Integer> {

    @Option(names = {"-i", "--input"}, required = true,
            description = "Directory with source GeoJSON/shapefile datasets (see DATA_SOURCES.md).")
    Path input;

    @Option(names = {"-d", "--database"}, required = true,
            description = "Target SQLite file, e.g. plugins/TerraForge/terraforge.db")
    Path database;

    @Option(names = "--bbox", split = ",", arity = "4",
            description = "Optional clip box: latMin,lonMin,latMax,lonMax")
    double[] bbox;

    @Override
    public Integer call() {
        return NotYetImplemented.report("prepare-geo", "Phase 7 (geographic database)");
    }
}
