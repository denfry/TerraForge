package dev.terraforge.cli;

import dev.terraforge.cli.dem.DemSource;
import dev.terraforge.cli.dem.DemTranscoder;
import dev.terraforge.cli.dem.HgtDemSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Transcodes source DEM rasters (SRTM HGT) into {@code .tfdem} tiles.
 *
 * <p>Reading heavyweight raster formats happens only here; the server reads the prepared tiles.
 *
 * <p>GeoTIFF input is recognised but not yet transcoded -- the command says so and points at a GDAL
 * one-liner instead of failing silently or, worse, writing partial tiles.
 */
@Command(name = "prepare-dem", description = "Convert source DEM rasters into .tfdem tiles.")
public final class PrepareDemCommand implements Callable<Integer> {

    @Option(names = {"-i", "--input"}, required = true,
            description = "Directory containing source DEM files (SRTM HGT).")
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
        if (!Files.isDirectory(input)) {
            System.err.println("Input directory does not exist: " + input);
            return 66; // EX_NOINPUT
        }

        int demEncoding;
        try {
            demEncoding = DemTranscoder.parseEncoding(encoding);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 64; // EX_USAGE
        }

        List<Path> hgtFiles;
        long geotiffCount;
        try (Stream<Path> files = Files.walk(input)) {
            List<Path> all = files.filter(Files::isRegularFile).toList();
            hgtFiles = all.stream()
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".hgt"))
                    .sorted()
                    .toList();
            geotiffCount = all.stream()
                    .map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                    .filter(name -> name.endsWith(".tif") || name.endsWith(".tiff"))
                    .count();
        } catch (IOException e) {
            System.err.println("Cannot scan " + input + ": " + e.getMessage());
            return 74; // EX_IOERR
        }

        if (geotiffCount > 0) {
            System.err.println(geotiffCount
                    + " GeoTIFF file(s) found and skipped -- GeoTIFF input is not transcoded by this build.");
            System.err.println("Convert them once with GDAL, then prepare the result:");
            System.err.println("  gdal_translate -of SRTMHGT input.tif N50E008.hgt");
        }

        if (hgtFiles.isEmpty()) {
            System.err.println("No .hgt tiles found under " + input + " -- nothing was written.");
            return 66;
        }

        var transcoder = new DemTranscoder(output, demEncoding, overwrite);
        List<String> failures = new ArrayList<>();
        int written = 0;
        int skipped = 0;

        for (Path file : hgtFiles) {
            try (DemSource source = HgtDemSource.open(file)) {
                DemTranscoder.Result result = transcoder.transcode(source);
                if (result.skipped()) {
                    skipped++;
                    System.out.printf(Locale.ROOT, "  %-10s exists, skipped%n", source.key());
                } else {
                    written++;
                    System.out.printf(Locale.ROOT, "  %-10s %d x %d, %.2f%% void%n",
                            source.key(), source.width(), source.height(), result.voidPercentage());
                }
            } catch (IOException | RuntimeException e) {
                failures.add(file.getFileName() + ": " + e.getMessage());
            }
        }

        System.out.printf(Locale.ROOT, "%nPrepared %d tile(s) in %s (%d skipped, %d failed).%n",
                written, output, skipped, failures.size());
        failures.forEach(failure -> System.err.println("  failed: " + failure));
        // A partial run is still useful -- a missing tile is a coverage gap, not a fatal error --
        // but the exit code has to say that not everything was prepared.
        return failures.isEmpty() ? 0 : 65; // EX_DATAERR
    }
}
