package dev.terraforge.cli;

import dev.terraforge.cli.dem.DemSource;
import dev.terraforge.cli.dem.BathymetryDemSource;
import dev.terraforge.cli.dem.DemTranscoder;
import dev.terraforge.cli.dem.GeoTiffDemFile;
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
 * <p>Two input formats: SRTM {@code .hgt}, one file per degree cell, and north-up WGS84 GeoTIFF,
 * which is sliced into the degree cells it covers. A GeoTIFF that is rotated or projected is
 * rejected with the GDAL command that fixes it, rather than silently misplaced.
 */
@Command(name = "prepare-dem", description = "Convert source DEM rasters (SRTM HGT, GeoTIFF) into .tfdem tiles.")
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

    @Option(names = "--bathymetry", description = "Mark every input raster as GEBCO bathymetry.")
    boolean bathymetry;

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
        List<Path> geotiffFiles;
        try (Stream<Path> files = Files.walk(input)) {
            List<Path> all = files.filter(Files::isRegularFile).toList();
            hgtFiles = all.stream()
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".hgt"))
                    .sorted()
                    .toList();
            geotiffFiles = all.stream()
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".tif") || name.endsWith(".tiff");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            System.err.println("Cannot scan " + input + ": " + e.getMessage());
            return 74; // EX_IOERR
        }

        if (hgtFiles.isEmpty() && geotiffFiles.isEmpty()) {
            System.err.println("No .hgt or .tif tiles found under " + input + " -- nothing was written.");
            return 66;
        }

        var transcoder = new DemTranscoder(output, demEncoding, overwrite);
        List<String> failures = new ArrayList<>();
        int written = 0;
        int skipped = 0;

        for (Path file : hgtFiles) {
            try (DemSource source = HgtDemSource.open(file)) {
                DemTranscoder.Result result = transcode(transcoder, source);
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

        // One GeoTIFF covers whatever extent its author chose, so it expands into one tile per
        // one-degree cell it touches.
        for (Path file : geotiffFiles) {
            try (GeoTiffDemFile raster = GeoTiffDemFile.open(file)) {
                for (var key : raster.tiles()) {
                    DemSource source = raster.sourceFor(key);
                    DemTranscoder.Result result = transcode(transcoder, source);
                    if (result.skipped()) {
                        skipped++;
                        System.out.printf(Locale.ROOT, "  %-10s exists, skipped%n", key);
                    } else {
                        written++;
                        System.out.printf(Locale.ROOT, "  %-10s %d x %d, %.2f%% void (from %s)%n",
                                key, source.width(), source.height(), result.voidPercentage(),
                                file.getFileName());
                    }
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

    private DemTranscoder.Result transcode(DemTranscoder transcoder, DemSource source) throws IOException {
        if (!bathymetry) {
            return transcoder.transcode(source);
        }
        try (DemSource marked = new BathymetryDemSource(source)) {
            return transcoder.transcode(marked);
        }
    }
}
