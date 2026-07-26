package dev.terraforge.cli;

import dev.terraforge.core.config.ConfigLoader;
import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.MinecraftPos;
import dev.terraforge.core.projection.Projection;
import dev.terraforge.core.projection.ProjectionRegistry;
import dev.terraforge.geo.dem.DemElevationProvider;
import dev.terraforge.geo.dem.DemTileKey;
import dev.terraforge.geo.dem.FileDemReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Reports how a configuration maps the Earth onto the world: projection, scale, and the block
 * extent of the configured region. Useful for sizing a world before preparing any data.
 */
@Command(name = "info", description = "Show projection, scale and region extent for a configuration.")
public final class InfoCommand implements Callable<Integer> {

    @Option(names = {"-c", "--config"}, description = "Path to terraforge.yml (defaults built in).")
    Path config;

    @Option(names = {"-d", "--dem"},
            description = "Prepared tile directory to report coverage for, e.g. "
                    + "plugins/TerraForge/data/dem")
    Path dem;

    @Override
    public Integer call() throws Exception {
        ConfigLoader loader = new ConfigLoader();
        TerraForgeConfig cfg = loader.validate(
                config == null ? TerraForgeConfig.defaults() : loader.load(config));

        Projection projection = new ProjectionRegistry()
                .create(cfg.earth().projection(), cfg.earth().origin().latitude());
        CoordinateTransformer transformer =
                new CoordinateTransformer(projection, cfg.earth().originPoint(), cfg.scale().blocksPerKm());

        GeoBounds region = cfg.testRegion().toBounds();
        MinecraftPos northWest = transformer.toMinecraft(region.maxLatitude(), region.minLongitude());
        MinecraftPos southEast = transformer.toMinecraft(region.minLatitude(), region.maxLongitude());
        long widthBlocks = Math.round(southEast.x() - northWest.x());
        long heightBlocks = Math.round(southEast.z() - northWest.z());
        long chunks = (widthBlocks / 16 + 1) * (heightBlocks / 16 + 1);

        System.out.println("World:        " + cfg.world().name());
        System.out.println("Projection:   " + projection.description());
        System.out.println("Origin:       " + transformer.origin() + "  -> Minecraft 0, 0");
        System.out.printf(java.util.Locale.ROOT, "Scale:        %.2f blocks/km (%.0f m per block)%n",
                cfg.scale().blocksPerKm(), transformer.metersPerBlock());
        System.out.println("Region:       " + cfg.testRegion().name() + " " + region);
        System.out.printf(java.util.Locale.ROOT, "Region NW:    x=%d z=%d%n", Math.round(northWest.x()), Math.round(northWest.z()));
        System.out.printf(java.util.Locale.ROOT, "Region SE:    x=%d z=%d%n", Math.round(southEast.x()), Math.round(southEast.z()));
        System.out.printf(java.util.Locale.ROOT, "Region size:  %d x %d blocks (~%,d chunks)%n", widthBlocks, heightBlocks, chunks);
        System.out.printf(java.util.Locale.ROOT, "Ground/block: %.0f m at the origin latitude%n",
                transformer.groundMetersPerBlock(cfg.earth().origin().latitude()));

        if (dem != null) {
            reportDemCoverage(region);
        }
        return 0;
    }

    /**
     * Coverage against the configured region, so a gap is found before pregeneration rather than by
     * a player walking into a wall of fallback elevation.
     */
    private void reportDemCoverage(GeoBounds region) throws java.io.IOException {
        FileDemReader reader = new FileDemReader(dem);
        int prepared = reader.tileCount();
        System.out.println();
        System.out.println("DEM tiles:    " + prepared + " prepared in " + dem);
        if (prepared == 0) {
            System.out.println("              run 'terraforge prepare-dem' before generating the world");
            return;
        }

        List<DemTileKey> missing = new ArrayList<>();
        for (int lat = (int) Math.floor(region.minLatitude()); lat < Math.ceil(region.maxLatitude()); lat++) {
            for (int lon = (int) Math.floor(region.minLongitude()); lon < Math.ceil(region.maxLongitude()); lon++) {
                DemTileKey key = new DemTileKey(lat, lon);
                if (!reader.exists(key)) {
                    missing.add(key);
                }
            }
        }
        System.out.println("DEM coverage: " + DemElevationProvider.coverageOf(reader));
        if (missing.isEmpty()) {
            System.out.println("Region gaps:  none");
        } else {
            System.out.println("Region gaps:  " + missing.size() + " tile(s) missing over the region");
            missing.stream().limit(20).forEach(key -> System.out.println("              " + key));
            if (missing.size() > 20) {
                System.out.println("              ... and " + (missing.size() - 20) + " more");
            }
        }
    }
}
