package dev.terraforge.plugin;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.config.ConfigLoader;
import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.projection.Projection;
import dev.terraforge.core.projection.ProjectionRegistry;
import dev.terraforge.core.terrain.VerticalScale;
import dev.terraforge.geo.dem.DemElevationProvider;
import dev.terraforge.geo.dem.FileDemReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper entry point.
 *
 * <p>Responsibilities are deliberately narrow: load and validate configuration, build the
 * coordinate system, detect optional integrations and wire the services together. All geography and
 * terrain work happens in the core, geo and generator modules -- this class must stay free of GIS
 * logic.
 */
public final class TerraForgePlugin extends JavaPlugin {

    private static final String LOG_PREFIX = "[TerraForge] ";

    private TerraForgeConfig config;
    private CoordinateTransformer transformer;
    private VerticalScale verticalScale;
    private CacheManager cacheManager;
    private FileDemReader demReader;
    private DemElevationProvider elevation;
    private IntegrationStatus integrations;

    @Override
    public void onEnable() {
        try {
            this.config = loadConfiguration();
        } catch (IOException | RuntimeException e) {
            getLogger().severe(LOG_PREFIX + "Invalid configuration: " + e.getMessage());
            getLogger().severe(LOG_PREFIX + "Fix plugins/TerraForge/terraforge.yml and restart. "
                    + "Refusing to enable rather than generating a broken world.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Projection projection = new ProjectionRegistry()
                .create(config.earth().projection(), config.earth().origin().latitude());
        this.transformer = new CoordinateTransformer(
                projection, config.earth().originPoint(), config.scale().blocksPerKm());
        this.verticalScale = new VerticalScale(
                config.terrain().seaLevel(),
                config.terrain().minY(),
                config.terrain().maxY(),
                config.terrain().verticalExaggeration(),
                config.terrain().metersPerBlock());
        this.cacheManager = new CacheManager(config.cache().memoryLimitMb());

        // A world with no prepared DEM still starts: every column falls back to
        // terrain.fallback-elevation, which is flat but honest, and the operator sees why below.
        Path demDirectory = getDataFolder().toPath()
                .resolve(config.data().dataDirectory())
                .resolve("dem");
        try {
            this.demReader = FileDemReader.open(demDirectory);
        } catch (IOException e) {
            getLogger().severe(LOG_PREFIX + "Cannot read DEM directory " + demDirectory + ": " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.elevation = new DemElevationProvider(demReader, cacheManager, config.cache().demTileCacheEntries());

        this.integrations = IntegrationStatus.detect(getServer().getPluginManager(), config);

        printBanner();
    }

    @Override
    public void onDisable() {
        if (cacheManager != null) {
            cacheManager.invalidateAll();
        }
        if (demReader != null) {
            demReader.close();
        }
        getLogger().info(LOG_PREFIX + "Disabled.");
    }

    private TerraForgeConfig loadConfiguration() throws IOException {
        Path file = getDataFolder().toPath().resolve("terraforge.yml");
        if (!Files.isRegularFile(file)) {
            saveResource("terraforge.yml", false);
        }
        ConfigLoader loader = new ConfigLoader();
        return loader.validate(loader.load(file));
    }

    private void printBanner() {
        String line = "====================================";
        getLogger().info(line);
        getLogger().info("TerraForge - Real Earth Engine");
        getLogger().info(line);
        getLogger().info("World:        " + config.world().name());
        getLogger().info("Scale:        " + config.scale().blocksPerKm() + " blocks/km ("
                + String.format("%.0f m", transformer.metersPerBlock()) + " per block)");
        getLogger().info("Projection:   " + transformer.projection().description());
        getLogger().info("Origin:       " + transformer.origin());
        getLogger().info("Vertical:     sea-level " + verticalScale.seaLevel()
                + ", exaggeration " + verticalScale.verticalExaggeration()
                + ", " + verticalScale.metersPerBlock() + " m/block");
        getLogger().info("Test region:  " + config.testRegion().name() + " " + config.testRegion().toBounds());
        getLogger().info("DEM:          " + demSummary());
        getLogger().info("Towny:        " + integrations.townyStatus());
        getLogger().info("BlueMap:      " + integrations.blueMapStatus());
        getLogger().info("Natural-only: " + (config.generation().naturalOnly() ? "ENABLED" : "DISABLED"));
        getLogger().info(line);
    }

    private String demSummary() {
        int tiles = 0;
        for (var ignored : demReader.availableTiles()) {
            tiles++;
        }
        if (tiles == 0) {
            return "no prepared tiles in " + demReader.directory()
                    + " -- terrain will be flat at " + config.terrain().fallbackElevation() + " m "
                    + "(run 'terraforge prepare-dem', see docs/dem.md)";
        }
        return tiles + " tiles " + elevation.coverage()
                + (elevation.hasBathymetry() ? ", with bathymetry" : ", land only");
    }

    // --- accessors used by the command and integration layers ---------------

    public TerraForgeConfig config() {
        return config;
    }

    public CoordinateTransformer transformer() {
        return transformer;
    }

    public VerticalScale verticalScale() {
        return verticalScale;
    }

    public CacheManager cacheManager() {
        return cacheManager;
    }

    /** Real elevation, straight from the prepared DEM tiles. */
    public DemElevationProvider elevation() {
        return elevation;
    }

    public IntegrationStatus integrations() {
        return integrations;
    }
}
