package dev.terraforge.cli;

import dev.terraforge.cli.landcover.AsciiGridLandcoverImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Converts an offline Arc/Info ASCII land-cover raster into the runtime's compact grid format. */
@Command(name = "prepare-landcover", description = "Convert an Arc/Info ASCII land-cover raster into .tflc.")
public final class PrepareLandcoverCommand implements Callable<Integer> {

    @Option(names = {"-i", "--input"}, required = true,
            description = "Source Arc/Info ASCII Grid (.asc), in WGS84 degrees.")
    Path input;

    @Option(names = {"-o", "--output"}, required = true,
            description = "Output prepared grid (.tflc), e.g. plugins/TerraForge/data/landcover/region.tflc")
    Path output;

    @Option(names = "--overwrite", description = "Replace an existing prepared grid.")
    boolean overwrite;

    @Override
    public Integer call() {
        if (!Files.isRegularFile(input)) {
            System.err.println("Land-cover input file does not exist: " + input);
            return 66;
        }
        if (Files.exists(output) && !overwrite) {
            System.err.println("Prepared land-cover grid already exists: " + output
                    + " (pass --overwrite to replace it)");
            return 65;
        }
        try {
            AsciiGridLandcoverImporter.importFile(input, output);
            System.out.println("Prepared land-cover grid in " + output + ".");
            return 0;
        } catch (IOException | RuntimeException exception) {
            System.err.println("Cannot prepare land cover: " + exception.getMessage());
            return 74;
        }
    }
}
