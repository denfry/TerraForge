package dev.terraforge.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Transcodes source DEM rasters (GeoTIFF, SRTM HGT) into {@code .tfdem} tiles.
 *
 * <p>Reading heavyweight raster formats happens only here; the server reads the prepared tiles.
 */
@Command(name = "prepare-dem", description = "Convert source DEM rasters into .tfdem tiles.")
public final class PrepareDemCommand implements Callable<Integer> {

    @Option(names = {"-i", "--input"}, required = true,
            description = "Directory containing source DEM files (GeoTIFF or SRTM HGT).")
    Path input;

    @Option(names = {"-o", "--output"}, required = true,
            description = "Output directory for .tfdem tiles, e.g. plugins/TerraForge/data/dem")
    Path output;

    @Option(names = "--encoding", defaultValue = "int16",
            description = "Sample encoding: int16 (compact) or float32 (sub-metre / bathymetry).")
    String encoding;

    @Option(names = "--overwrite", description = "Rewrite tiles that already exist.")
    boolean overwrite;

    @Override
    public Integer call() {
        return NotYetImplemented.report("prepare-dem", "Phase 4 (DEM provider)");
    }
}
