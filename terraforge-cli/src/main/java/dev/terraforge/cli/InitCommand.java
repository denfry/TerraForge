package dev.terraforge.cli;

import dev.terraforge.cli.setup.PluginLayout;
import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.coord.GeoPoint;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Creates an empty plugin data directory and the configuration that matches a bounding box.
 *
 * <p>Useful on its own when the data is prepared elsewhere -- on a workstation, into the same
 * layout -- and only the configured region has to agree with it.
 */
@Command(name = "init",
        description = "Create the plugin data directory and a terraforge.yml for one region.")
public final class InitCommand implements Callable<Integer> {

    private static final int EX_OK = 0;
    private static final int EX_USAGE = 64;
    private static final int EX_IOERR = 74;

    @Mixin RegionOptions region;

    @Option(names = {"-o", "--output"}, required = true,
            description = "Plugin data directory, e.g. server/plugins/TerraForge")
    Path output;

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

    @Option(names = "--replace-config", description = "Overwrite an existing terraforge.yml.")
    boolean replaceConfig;

    @Override
    public Integer call() {
        PluginLayout.Options options;
        try {
            options = layoutOptions();
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return EX_USAGE;
        }
        PluginLayout layout = new PluginLayout(System.out);
        try {
            TerraForgeConfig config = layout.create(output, options);
            System.out.println("Directories: " + output.resolve("data") + ", " + output.resolve("cache"));
            layout.printWorldInstructions(config, output, options.vertical());
            return EX_OK;
        } catch (IOException | RuntimeException exception) {
            System.err.println("Cannot initialise " + output + ": " + exception.getMessage());
            return EX_IOERR;
        }
    }

    /** Shared with {@code setup}, which builds the same layout before fetching anything. */
    PluginLayout.Options layoutOptions() {
        if ((originLatitude == null) != (originLongitude == null)) {
            throw new IllegalArgumentException("--origin-lat and --origin-lon must be given together");
        }
        if (!Double.isFinite(scale) || scale <= 0.0) {
            throw new IllegalArgumentException("--scale must be greater than 0");
        }
        GeoPoint origin = originLatitude == null ? null : new GeoPoint(originLatitude, originLongitude);
        return new PluginLayout.Options(region.bounds(), world, scale, origin, regionName, replaceConfig);
    }
}
