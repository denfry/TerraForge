package dev.terraforge.cli;

import dev.terraforge.cli.dem.DemTranscoder;
import dev.terraforge.cli.dem.SourceRasters;
import java.nio.file.Path;
import java.util.List;
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

    @Option(names = "--encoding", defaultValue = "auto",
            description = "Sample encoding: int16 (compact), float32 (sub-metre / bathymetry) "
                    + "or auto (default: match the source).")
    String encoding;

    @Option(names = "--samples-per-tile",
            description = "Force the samples per tile side (default: match the finest source; "
                    + "3601 is one arc-second).")
    Integer samplesPerTile;

    @Option(names = "--overwrite", description = "Rewrite tiles that already exist.")
    boolean overwrite;

    @Override
    public Integer call() throws Exception {
        DemTranscoder.Encoding selected;
        try {
            selected = DemTranscoder.Encoding.parse(encoding);
        } catch (IllegalArgumentException e) {
            System.err.println("terraforge: " + e.getMessage());
            return 2;
        }
        if (samplesPerTile != null && samplesPerTile < 2) {
            System.err.println("terraforge: --samples-per-tile must be at least 2");
            return 2;
        }

        List<Path> sources = SourceRasters.find(input);
        if (sources.isEmpty()) {
            System.err.println("terraforge: no .hgt, .tif or .tiff files under " + input);
            return 1;
        }

        DemTranscoder transcoder =
                new DemTranscoder(output, overwrite, samplesPerTile, selected, System.out::println);
        DemTranscoder.Result result = transcoder.run(sources);

        System.out.printf("%nWrote %d tile(s) to %s (%d skipped, %d empty).%n",
                result.tilesWritten(), output, result.tilesSkipped(), result.tilesEmpty());
        if (!result.ok()) {
            System.err.println("Failed tiles:");
            result.failures().forEach(failure -> System.err.println("  " + failure));
            return 1;
        }
        return result.tilesWritten() == 0 && result.tilesSkipped() == 0 ? 1 : 0;
    }
}
