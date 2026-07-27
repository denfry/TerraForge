package dev.terraforge.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Entry point of the offline data preparation tool.
 *
 * <p>Everything expensive happens here rather than on the server: DEM transcoding, geodata import
 * and pregeneration planning. A running Paper server only ever reads prepared tiles and a prepared
 * database.
 */
@Command(
        name = "terraforge",
        mixinStandardHelpOptions = true,
        versionProvider = TerraForgeCli.VersionProvider.class,
        description = "TerraForge offline data preparation.",
        subcommands = {
                InfoCommand.class,
                PrepareDemCommand.class,
                PrepareBoundariesCommand.class,
                PrepareCitiesCommand.class,
                PrepareLandcoverCommand.class,
                PrepareGeoCommand.class,
                PrepareRegionCommand.class,
                ValidateCommand.class,
                PregenerateCommand.class,
        })
public final class TerraForgeCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new TerraForgeCli()).execute(args);
        System.exit(exitCode);
    }

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = TerraForgeCli.class.getPackage().getImplementationVersion();
            return new String[]{"terraforge " + (version == null ? "(dev build)" : version)};
        }
    }
}
