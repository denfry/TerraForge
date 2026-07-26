package dev.terraforge.plugin;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.config.ConfigLoader;
import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.projection.Projection;
import dev.terraforge.core.projection.ProjectionRegistry;
import dev.terraforge.core.terrain.VerticalScale;
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
    private IntegrationStatus integrations;
    private DemServices dem;

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
        this.integrations = IntegrationStatus.detect(getServer().getPluginManager(), config);

        try {
            this.dem = DemServices.load(getDataFolder().toPath(), config, cacheManager, getLogger());
        } catch (IOException e) {
            // Unreadable data directory, not absent data: absence is handled inside DemServices.
            getLogger().severe(LOG_PREFIX + "Cannot read the DEM data directory: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        printBanner();
    }

    @Override
    public void onDisable() {
        if (dem != null) {
            try {
                dem.close();
            } catch (IOException e) {
                getLogger().warning(LOG_PREFIX + "Failed to release DEM resources: " + e.getMessage());
            }
        }
        if (cacheManager != null) {
            cacheManager.invalidateAll();
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
        getLogger().info("DEM tiles:    " + (dem.tileCount() == 0
                ? "NONE (flat world -- run 'terraforge prepare-dem')"
                : dem.tileCount() + " covering " + dem.elevation().coverage()));
        getLogger().info("Towny:        " + integrations.townyStatus());
        getLogger().info("BlueMap:      " + integrations.blueMapStatus());
        getLogger().info("Natural-only: " + (config.generation().naturalOnly() ? "ENABLED" : "DISABLED"));
        getLogger().info(line);
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

    public IntegrationStatus integrations() {
        return integrations;
    }

    /** Elevation stack; present once the plugin has enabled successfully. */
    public DemServices dem() {
        return dem;
    }
}
