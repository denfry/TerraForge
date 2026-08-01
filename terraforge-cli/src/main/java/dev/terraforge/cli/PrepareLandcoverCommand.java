package dev.terraforge.cli;

import dev.terraforge.cli.landcover.AsciiGridLandcoverImporter;
import dev.terraforge.cli.landcover.GeoTiffLandcoverImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Converts an offline land-cover raster into the runtime's compact grid format.
 *
 * <p>Two source shapes are supported, and they differ in what {@code --output} means. An Arc/Info
 * ASCII grid is one region and becomes one {@code .tflc} file. A GeoTIFF -- the form ESA WorldCover
 * is published in -- covers several degree cells at a resolution far finer than the world needs, so
 * it is sliced and subsampled into one file per cell and {@code --output} is the directory holding
 * them.
 */
@Command(name = "prepare-landcover",
        description = "Convert an ASCII or GeoTIFF land-cover raster into prepared .tflc grids.")
public final class PrepareLandcoverCommand implements Callable<Integer> {

    private static final int EX_OK = 0;
    private static final int EX_DATAERR = 65;
    private static final int EX_NOINPUT = 66;
    private static final int EX_IOERR = 74;

    @Option(names = {"-i", "--input"}, required = true,
            description = "Source raster: Arc/Info ASCII Grid (.asc) or GeoTIFF (.tif), in WGS84 degrees.")
    Path input;

    @Option(names = {"-o", "--output"}, required = true,
            description = "Prepared grid (.tflc) for ASCII input, or the directory to fill for GeoTIFF input.")
    Path output;

    @Option(names = "--samples-per-degree", defaultValue = "600",
            description = "GeoTIFF only: output cells per degree. Must divide the source resolution. "
                    + "Default: ${DEFAULT-VALUE} (about 185 m).")
    int samplesPerDegree;

    @Option(names = "--overwrite", description = "Replace prepared grids that already exist.")
    boolean overwrite;

    @Override
    public Integer call() {
        if (!Files.isRegularFile(input)) {
            System.err.println("Land-cover input file does not exist: " + input);
            return EX_NOINPUT;
        }
        String name = input.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".tif") || name.endsWith(".tiff") ? importGeoTiff() : importAsciiGrid();
    }

    private Integer importAsciiGrid() {
        if (Files.exists(output) && !overwrite) {
            System.err.println("Prepared land-cover grid already exists: " + output
                    + " (pass --overwrite to replace it)");
            return EX_DATAERR;
        }
        try {
            AsciiGridLandcoverImporter.importFile(input, output);
            System.out.println("Prepared land-cover grid in " + output + ".");
            return EX_OK;
        } catch (IOException | RuntimeException exception) {
            System.err.println("Cannot prepare land cover: " + exception.getMessage());
            return EX_IOERR;
        }
    }

    private Integer importGeoTiff() {
        try {
            Files.createDirectories(output);
            GeoTiffLandcoverImporter.Result result =
                    GeoTiffLandcoverImporter.importFile(input, output, samplesPerDegree, null, overwrite);
            result.skipped().forEach(reason -> System.out.println("  skipped: " + reason));
            if (result.written().isEmpty() && result.skipped().isEmpty()) {
                System.err.println("The raster covers no complete degree cell; nothing was prepared.");
                return EX_DATAERR;
            }
            System.out.println("Prepared " + result.written().size() + " land-cover grid(s) in " + output + ".");
            return EX_OK;
        } catch (IOException | RuntimeException exception) {
            System.err.println("Cannot prepare land cover: " + exception.getMessage());
            return EX_IOERR;
        }
    }
}
