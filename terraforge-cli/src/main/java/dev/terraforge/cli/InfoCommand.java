package dev.terraforge.cli;

import dev.terraforge.core.config.ConfigLoader;
import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.MinecraftPos;
import dev.terraforge.core.projection.Projection;
import dev.terraforge.core.projection.ProjectionRegistry;
import dev.terraforge.geo.dem.DemReader;
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
        reportDemCoverage(cfg, region);
        return 0;
    }

    /**
     * Reports which one-degree cells of the configured region have a prepared tile, so a missing
     * tile is found before a player walks into the hole it leaves.
     */
    private void reportDemCoverage(TerraForgeConfig cfg, GeoBounds region) throws java.io.IOException {
        Path base = config == null ? Path.of(".") : config.toAbsolutePath().getParent();
        Path demDirectory = base.resolve(cfg.data().dataDirectory()).resolve("dem");

        try (DemReader reader = FileDemReader.open(demDirectory)) {
            List<DemTileKey> missing = new ArrayList<>();
            int required = 0;
            for (int lat = (int) Math.floor(region.minLatitude()); lat < region.maxLatitude(); lat++) {
                for (int lon = (int) Math.floor(region.minLongitude()); lon < region.maxLongitude(); lon++) {
                    required++;
                    DemTileKey key = new DemTileKey(lat, lon);
                    if (!reader.exists(key)) {
                        missing.add(key);
                    }
                }
            }

            System.out.println();
            System.out.println("DEM directory: " + demDirectory);
            System.out.printf(java.util.Locale.ROOT, "DEM coverage:  %d of %d region tiles prepared%s%n",
                    required - missing.size(), required,
                    reader.hasBathymetry() ? " (with bathymetry)" : "");
            if (!missing.isEmpty()) {
                System.out.printf(java.util.Locale.ROOT,
                        "Missing:       %d tile(s); terrain there falls back to %.1f m%n",
                        missing.size(), cfg.terrain().fallbackElevation());
                String sample = missing.stream().limit(12).map(DemTileKey::toString)
                        .collect(java.util.stream.Collectors.joining(", "));
                System.out.println("               " + sample + (missing.size() > 12 ? ", ..." : ""));
            }
        }
    }
}
