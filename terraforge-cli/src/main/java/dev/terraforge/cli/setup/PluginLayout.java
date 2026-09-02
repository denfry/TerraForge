package dev.terraforge.cli.setup;

import dev.terraforge.core.config.VerticalProfile;

import dev.terraforge.core.config.ConfigLoader;
import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.GeoPoint;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Creates the plugin data directory a prepared world needs, and the configuration that describes it.
 *
 * <p>The directories themselves are cheap; the configuration is the point. Three settings must agree
 * with the data that was prepared, and getting them wrong is expensive in a way nothing detects
 * later: {@code earth.origin} decides where the region lands in block coordinates, and changing it
 * after chunks exist leaves a permanent seam. Deriving them from the same bounding box the data was
 * prepared for removes the chance of disagreement.
 *
 * <p>An existing {@code terraforge.yml} is never silently rewritten. It may be the only record of
 * where a populated world's origin is.
 */
public final class PluginLayout {

    /** Sub-directories the runtime expects to exist. */
    private static final List<String> DIRECTORIES = List.of("data/dem", "data/landcover", "cache");

    private final PrintStream out;

    public PluginLayout(PrintStream out) {
        this.out = out;
    }

    /**
     * @param origin     block (0, 0) of the world; {@code null} means the centre of {@code bounds}
     * @param regionName label for the prepared region, shown by {@code /earth info}
     */
    public record Options(GeoBounds bounds, String worldName, double blocksPerKm, GeoPoint origin,
                          String regionName, boolean replace, VerticalProfile vertical) {

        /** @param vertical {@code null} means "whatever suits the bounding box" */
        public Options {
            if (vertical == null) {
                vertical = VerticalProfile.forBounds(bounds);
            }
        }

        public Options(GeoBounds bounds, String worldName, double blocksPerKm, GeoPoint origin,
                       String regionName, boolean replace) {
            this(bounds, worldName, blocksPerKm, origin, regionName, replace, null);
        }
    }

    /**
     * Creates the directory tree and writes {@code terraforge.yml}.
     *
     * @return the configuration on disk -- the existing one when it was kept
     */
    public TerraForgeConfig create(Path pluginDirectory, Options options) throws IOException {
        for (String directory : DIRECTORIES) {
            Files.createDirectories(pluginDirectory.resolve(directory));
        }
        Path file = pluginDirectory.resolve("terraforge.yml");
        ConfigLoader loader = new ConfigLoader();
        if (Files.exists(file) && !options.replace()) {
            TerraForgeConfig existing = loader.validate(loader.load(file));
            out.println("Config:     kept existing " + file
                    + " (pass --replace-config to overwrite it)");
            warnAboutMismatch(existing, options);
            return existing;
        }
        TerraForgeConfig config = loader.validate(configure(options));
        loader.write(config, file);
        out.println("Config:     " + file);
        out.println("Vertical:   " + options.vertical().describe());
        if (options.vertical().needsDatapack()) {
            Path pack = DimensionDatapack.write(pluginDirectory, options.vertical());
            out.println("Datapack:   " + pack);
        }
        return config;
    }

    /** Defaults, with the handful of settings the bounding box determines applied on top. */
    public static TerraForgeConfig configure(Options options) {
        TerraForgeConfig defaults = TerraForgeConfig.defaults();
        GeoPoint origin = options.origin() == null ? options.bounds().center() : options.origin();
        return new TerraForgeConfig(
                new TerraForgeConfig.WorldSection(options.worldName()),
                new TerraForgeConfig.ScaleSection(options.blocksPerKm()),
                new TerraForgeConfig.EarthSection(
                        new TerraForgeConfig.OriginSection(origin.latitude(), origin.longitude()),
                        defaults.earth().projection()),
                options.vertical().applyTo(defaults.terrain()),
                defaults.water(),
                defaults.biomes(),
                defaults.generation(),
                defaults.pregeneration(),
                defaults.infrastructure(),
                defaults.data(),
                defaults.cache(),
                defaults.towny(),
                defaults.bluemap(),
                defaults.debug(),
                new TerraForgeConfig.TestRegionSection(options.regionName(),
                        options.bounds().minLatitude(), options.bounds().maxLatitude(),
                        options.bounds().minLongitude(), options.bounds().maxLongitude()));
    }

    /**
     * A kept config that disagrees with the box being prepared is the one case where doing nothing is
     * worse than saying something: the data lands outside the region the server will read.
     */
    private void warnAboutMismatch(TerraForgeConfig existing, Options options) {
        GeoBounds configured = existing.testRegion().toBounds();
        GeoBounds requested = options.bounds();
        if (!configured.intersects(requested)) {
            out.println("            WARNING: its test-region " + configured
                    + " does not overlap the region being prepared " + requested + ".");
        }
        if (!existing.world().name().equals(options.worldName())) {
            out.println("            WARNING: its world.name is '" + existing.world().name()
                    + "', not '" + options.worldName() + "'.");
        }
    }

    /** The generator registration an operator still has to make; nothing outside the plugin directory is touched. */
    public void printWorldInstructions(TerraForgeConfig config, Path pluginDirectory,
                                       VerticalProfile vertical) {
        out.println();
        out.println("Register the generator in the server's bukkit.yml:");
        out.println();
        out.println("worlds:");
        out.println("  " + config.world().name() + ":");
        out.println("    generator: TerraForge");
        out.println();
        if (vertical.needsDatapack()) {
            out.println(DimensionDatapack.instructions(
                    pluginDirectory.resolve("datapack").resolve("terraforge-world-height"),
                    config.world().name(), vertical));
        }
        out.printf(Locale.ROOT, "Then start Paper. The world '%s' generates from the prepared data; "
                        + "'/earth info' reports the origin %.4f, %.4f.%n",
                config.world().name(), config.earth().origin().latitude(),
                config.earth().origin().longitude());
    }
}
