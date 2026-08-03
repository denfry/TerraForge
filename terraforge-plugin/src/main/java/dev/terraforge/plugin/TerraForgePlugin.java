package dev.terraforge.plugin;

import dev.terraforge.bluemap.BlueMapMarkerHook;
import dev.terraforge.bluemap.TerraForgeBlueMapHook;
import dev.terraforge.core.api.GeoBoundaryProjector;
import dev.terraforge.core.api.GeoMarkerService;
import dev.terraforge.core.api.GeoPointResolver;
import dev.terraforge.core.api.InMemoryGeoMarkerService;
import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.config.ConfigLoader;
import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.config.VerticalProfile;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.projection.Projection;
import dev.terraforge.core.projection.ProjectionRegistry;
import dev.terraforge.core.terrain.VerticalScale;
import dev.terraforge.geo.database.BlockCountryResolver;
import dev.terraforge.geo.database.JtsBoundaryProjector;
import dev.terraforge.geo.database.SqliteBoundaryIndex;
import dev.terraforge.geo.dem.DemElevationProvider;
import dev.terraforge.geo.dem.FileDemReader;
import dev.terraforge.geo.landcover.FileLandcoverProvider;
import dev.terraforge.geo.karst.SqliteKarstProvider;
import dev.terraforge.geo.marker.GeoMarkerPopulator;
import dev.terraforge.geo.water.IndexedWaterProvider;
import dev.terraforge.geo.water.SqliteWaterProvider;
import dev.terraforge.generator.TerraForgeChunkGenerator;
import dev.terraforge.generator.TerrainStack;
import dev.terraforge.plugin.world.BootstrapDatapackService;
import dev.terraforge.plugin.world.DemDataFingerprint;
import dev.terraforge.plugin.world.LiveWorldSnapshot;
import dev.terraforge.plugin.world.LiveWorldVerifier;
import dev.terraforge.plugin.world.ManagedWorldManifest;
import dev.terraforge.plugin.world.ManagedWorldManifestStore;
import dev.terraforge.plugin.world.ManagedWorldStartupVerifier;
import dev.terraforge.towny.SqliteTownGeoService;
import dev.terraforge.towny.TownGeoListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
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
    // Registered at https://bstats.org/plugin/bukkit/TerraForge -- replace before release.
    private static final int BSTATS_PLUGIN_ID = 33013;

    private TerraForgeConfig config;
    private CoordinateTransformer transformer;
    private VerticalScale verticalScale;
    private CacheManager cacheManager;
    private FileDemReader demReader;
    private DemElevationProvider elevation;
    private FileLandcoverProvider landcover;
    private TerrainStack terrain;
    private SqliteBoundaryIndex boundaries;
    private SqliteTownGeoService townGeography;
    private PregenerationJob pregeneration;
    private IntegrationStatus integrations;
    private InMemoryGeoMarkerService markers;
    private TerraForgeBlueMapHook blueMap;
    private DebugOverlay debugOverlay;
    private boolean managedWorldReady;

    /** The full name Paper registers the bootstrap-discovered height pack under: plugin name + id. */
    private static final String MANAGED_DATAPACK_NAME = "TerraForge/" + BootstrapDatapackService.DATAPACK_ID;

    @Override
    public void onEnable() {
        try {
            initializeTerrain();
        } catch (IOException | RuntimeException e) {
            getLogger().severe(LOG_PREFIX + "Invalid configuration: " + e.getMessage());
            getLogger().severe(LOG_PREFIX + "Fix plugins/TerraForge/terraforge.yml and restart. "
                    + "Refusing to enable rather than generating a broken world.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.integrations = IntegrationStatus.detect(getServer().getPluginManager(), config);
        initializeBoundaryServices();
        initializeMarkers();
        initializeTownyIntegration();
        initializeBlueMapIntegration();
        initializeMetrics();
        verifyManagedWorldOnStartup();
        var earthCommand = getCommand("earth");
        if (earthCommand == null) {
            throw new IllegalStateException("plugin.yml is missing the earth command");
        }
        var executor = new EarthCommand(this);
        earthCommand.setExecutor(executor);
        earthCommand.setTabCompleter(executor);
        printBanner();
    }

    /**
     * Builds the immutable terrain stack once.
     *
     * <p>Multiverse probes a plugin's generator before it enables the plugin. Keeping this work
     * idempotent lets that probe obtain a real generator without racing {@link #onEnable()}.
     */
    private synchronized void initializeTerrain() throws IOException {
        if (terrain != null) {
            return;
        }
        this.config = loadConfiguration();
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
            throw new IOException("Cannot read DEM directory " + demDirectory + ": " + e.getMessage(), e);
        }
        this.elevation = new DemElevationProvider(demReader, cacheManager, config.cache().demTileCacheEntries());

        this.terrain = TerrainStack.create(config, transformer, verticalScale, elevation, cacheManager,
                loadWaterProvider(), loadLandcoverProvider(), loadKarstProvider());
        this.boundaries = loadBoundaryIndex();
    }

    @Override
    public void onDisable() {
        if (blueMap != null) {
            blueMap.disable();
            blueMap = null;
        }
        if (debugOverlay != null) {
            debugOverlay.shutdown();
            debugOverlay = null;
        }
        if (townGeography != null) {
            // Applies whatever town annotations are still queued before the JVM goes away.
            townGeography.close();
            townGeography = null;
        }
        if (cacheManager != null) {
            cacheManager.invalidateAll();
        }
        if (demReader != null) {
            demReader.close();
        }
        if (landcover != null) {
            landcover.close();
            landcover = null;
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
        getLogger().info("Relief:       " + verticalScale.earthFit());
        if (verticalScale.flattensRealTerrain()) {
            getLogger().warning(LOG_PREFIX + "This world can show less than "
                    + Math.round(verticalScale.highestUncompressedElevation())
                    + " m of relief, so mountain ranges will generate as one plateau. Raise "
                    + "terrain.meters-per-block, or raise terrain.max-y with a matching dimension "
                    + "type -- see docs/vertical-scale.md. Changing either after chunks exist "
                    + "leaves a permanent seam.");
        }
        getLogger().info("Test region:  " + config.testRegion().name() + " " + config.testRegion().toBounds());
        getLogger().info("DEM:          " + demSummary());
        getLogger().info("Towny:        " + integrations.townyStatus());
        getLogger().info("BlueMap:      " + integrations.blueMapStatus());
        getLogger().info("Natural-only: " + (config.generation().naturalOnly() ? "ENABLED" : "DISABLED"));
        getLogger().info(line);
    }

    /**
     * Paper asks for the generator when a world declares {@code generator: TerraForge}. The world
     * name is ignored on purpose: every TerraForge world is the same planet, and which part of it a
     * world shows is decided by the projection and origin, not by its name.
     */
    @Override
    public org.bukkit.generator.ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        try {
            initializeTerrain();
        } catch (IOException | RuntimeException exception) {
            getLogger().severe(LOG_PREFIX + "Cannot provide generator for " + worldName + ": "
                    + exception.getMessage());
            return null;
        }
        return terrain.chunkGenerator();
    }

    /**
     * Verifies a staged managed Earth world against the live server before any managed operation is
     * allowed to touch it.
     *
     * <p>A manifest in {@code PENDING_RESTART}, {@code CREATING} or {@code READY} means TerraForge
     * previously staged (or already verified) a primary {@code earth} world -- exactly the states
     * {@link BootstrapDatapackService} discovers a datapack for, so if the server got this far with
     * such a manifest present, the datapack fingerprint was already fail-closed verified at bootstrap.
     * This step covers what bootstrap cannot see: the live world's identity, generator, height and
     * enabled datapack. No manifest, or one that is {@code ABSENT}/{@code INVALID}, leaves managed
     * operations disabled without touching the file.
     */
    private void verifyManagedWorldOnStartup() {
        var manifests = new ManagedWorldManifestStore(getDataFolder().toPath());
        var verifier = new ManagedWorldStartupVerifier(manifests, new LiveWorldVerifier());
        try {
            managedWorldReady = verifier.verifyOnEnable(this::captureLiveWorldSnapshot,
                    failure -> getLogger().severe(LOG_PREFIX + "Managed Earth verification failed: " + failure));
        } catch (IOException exception) {
            getLogger().severe(LOG_PREFIX + "Managed Earth verification could not run: " + exception.getMessage());
            managedWorldReady = false;
        }
        if (!managedWorldReady) {
            getLogger().warning(LOG_PREFIX + "Managed Earth world is not verified; managed pregeneration and "
                    + "world commands stay disabled until the world is re-staged.");
        }
    }

    /**
     * Reads what {@link ManagedWorldStartupVerifier} needs from the live server for one manifest.
     *
     * <p>Config and data fingerprints are recomputed from the live server's own configuration and DEM
     * directory -- via {@link VerticalProfile#fingerprint()} and {@link DemDataFingerprint#of} -- using
     * exactly the same algorithms staging is expected to use when it records a manifest. Echoing the
     * manifest's own value back here would make {@link dev.terraforge.plugin.world.LiveWorldVerifier}'s
     * comparison a tautology that can never catch config or data drift.
     */
    private LiveWorldSnapshot captureLiveWorldSnapshot(ManagedWorldManifest manifest) {
        org.bukkit.World world = getServer().getWorld(manifest.worldName());
        if (world == null) {
            return new LiveWorldSnapshot("", false, false, 0, 0, false, "", "", "");
        }
        boolean primary = !getServer().getWorlds().isEmpty() && getServer().getWorlds().get(0).equals(world);
        boolean terraForgeGenerator = world.getGenerator() instanceof TerraForgeChunkGenerator;
        var datapack = getServer().getDatapackManager().getPack(MANAGED_DATAPACK_NAME);
        boolean datapackEnabled = datapack != null && datapack.isEnabled();
        String configFingerprint = VerticalProfile.from(config.terrain()).fingerprint();
        String dataFingerprint = DemDataFingerprint.of(demReader.directory());
        return new LiveWorldSnapshot(world.getName(), primary, terraForgeGenerator, world.getMinHeight(),
                world.getMaxHeight(), datapackEnabled, configFingerprint, dataFingerprint,
                manifest.datapackFingerprint());
    }

    /** True once the managed Earth world has been verified against the live server this run. */
    public boolean managedWorldReady() {
        return managedWorldReady;
    }

    void validateConfigurationForReload() throws IOException {
        ConfigLoader loader = new ConfigLoader();
        loader.validate(loader.load(getDataFolder().toPath().resolve("terraforge.yml")));
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

    private dev.terraforge.core.data.WaterProvider loadWaterProvider() {
        Path database = preparedDatabasePath();
        if (!Files.isRegularFile(database)) {
            getLogger().info(LOG_PREFIX + "Water: no prepared database; using elevation fallback.");
            return new dev.terraforge.core.data.SeaLevelWaterProvider(elevation);
        }
        try {
            IndexedWaterProvider provider = SqliteWaterProvider.load(database);
            if (provider != null) {
                getLogger().info(LOG_PREFIX + "Water: loaded prepared natural water features.");
                return provider;
            }
            getLogger().info(LOG_PREFIX + "Water: prepared database has no water features; using elevation fallback.");
        } catch (IOException exception) {
            getLogger().warning(LOG_PREFIX + "Water: cannot load " + database + ": "
                    + exception.getMessage() + "; using elevation fallback.");
        }
        return new dev.terraforge.core.data.SeaLevelWaterProvider(elevation);
    }

    private SqliteBoundaryIndex loadBoundaryIndex() {
        Path database = preparedDatabasePath();
        if (!Files.isRegularFile(database)) {
            getLogger().info(LOG_PREFIX + "Boundaries: no prepared database; country lookups unavailable.");
            return null;
        }
        try {
            SqliteBoundaryIndex index = SqliteBoundaryIndex.load(database);
            getLogger().info(LOG_PREFIX + "Geography: loaded " + index.countryCount() + " countries, "
                    + index.regionCount() + " regions and " + index.cityCount() + " cities.");
            return index;
        } catch (IOException exception) {
            getLogger().warning(LOG_PREFIX + "Boundaries: cannot load " + database + ": "
                    + exception.getMessage() + "; country lookups unavailable.");
            return null;
        }
    }

    private dev.terraforge.core.data.KarstProvider loadKarstProvider() {
        if (!config.generation().caves()) return dev.terraforge.core.data.KarstProvider.absent();
        Path database = preparedDatabasePath();
        if (!Files.isRegularFile(database)) return dev.terraforge.core.data.KarstProvider.absent();
        try { return SqliteKarstProvider.load(database); }
        catch (IOException exception) { getLogger().warning(LOG_PREFIX + "Karst: cannot load prepared data: " + exception.getMessage()); return dev.terraforge.core.data.KarstProvider.absent(); }
    }

    private Path preparedDatabasePath() {
        Path pluginRoot = getDataFolder().toPath().toAbsolutePath().normalize();
        Path database = pluginRoot.resolve(config.data().databaseFile()).normalize();
        if (!database.startsWith(pluginRoot)) {
            throw new IllegalArgumentException("data.database-file must stay inside the plugin directory");
        }
        return database;
    }

    /**
     * Registers the geography services other plugins consume through Bukkit's ServicesManager.
     *
     * <p>Boundary projection and point→country resolution both depend on the prepared database and
     * the live coordinate system, so they are registered only when a database was loaded. NewTowny
     * (and any other plugin) soft-depends on TerraForge and loads them by interface.
     */
    private void initializeBoundaryServices() {
        if (boundaries == null) {
            return;
        }
        getServer().getServicesManager().register(GeoBoundaryProjector.class,
                new JtsBoundaryProjector(boundaries, transformer), this, org.bukkit.plugin.ServicePriority.Normal);
        getServer().getServicesManager().register(GeoPointResolver.class,
                new BlockCountryResolver(boundaries, transformer), this, org.bukkit.plugin.ServicePriority.Normal);
        getLogger().info(LOG_PREFIX + "Boundary services registered for other plugins.");
    }

    /**
     * Publishes the prepared geography into the marker registry.
     *
     * <p>The registry exists whether or not BlueMap is installed: other plugins register their own
     * markers through it, and a later BlueMap install picks up everything already there.
     */
    private void initializeMarkers() {        this.markers = new InMemoryGeoMarkerService();
        var bluemapConfig = config.bluemap();
        int published = GeoMarkerPopulator.populate(markers, boundaries,
                GeoMarkerPopulator.Options.defaults(
                        bluemapConfig != null && bluemapConfig.cityMarkers(),
                        bluemapConfig != null && bluemapConfig.countryLabels()));
        getLogger().info(LOG_PREFIX + "Markers: published " + published + " geographic markers.");
    }

    /**
     * bStats respects the server operator's own opt-out in {@code plugins/bStats/config.yml}; no
     * TerraForge-side toggle is needed.
     */
    private void initializeMetrics() {
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("projection", () -> config.earth().projection()));
        metrics.addCustomChart(new SimplePie("towny_integration",
                () -> integrations.townyAvailable() ? "enabled" : "disabled"));
        metrics.addCustomChart(new SimplePie("bluemap_integration",
                () -> integrations.blueMapAvailable() ? "enabled" : "disabled"));
    }

    private void initializeBlueMapIntegration() {
        if (!integrations.blueMapAvailable()) {
            return;
        }
        var bluemapConfig = config.bluemap();
        blueMap = new BlueMapMarkerHook(markers, transformer, this::markerSurfaceY,
                () -> getServer().getWorld(config.world().name()),
                bluemapConfig.cityMarkers(), bluemapConfig.countryLabels(), getLogger());
        blueMap.enable();
        getLogger().info(LOG_PREFIX + "BlueMap marker integration enabled.");
    }

    /**
     * Block height a marker floats at: the real surface, never below sea level so that a coastal
     * or island label does not end up under water.
     */
    private double markerSurfaceY(double latitude, double longitude) {
        double meters = elevation.elevationAt(latitude, longitude);
        if (dev.terraforge.core.data.ElevationProvider.isNoData(meters)) {
            meters = config.terrain().fallbackElevation();
        }
        return Math.max(verticalScale.toBlockY(meters), verticalScale.seaLevel());
    }

    private void initializeTownyIntegration() {
        if (!getServer().getPluginManager().isPluginEnabled("Towny") || boundaries == null) return;
        townGeography = new SqliteTownGeoService(transformer, elevation, boundaries, preparedDatabasePath(),
                message -> getLogger().warning(LOG_PREFIX + message));
        getServer().getPluginManager().registerEvents(new TownGeoListener(this, townGeography), this);
        getLogger().info(LOG_PREFIX + "Towny/NewTowny geography integration enabled.");
    }

    /**
     * Land cover is catalogued, not loaded: the grids stay on disk and are read on demand into a
     * bounded cache. A whole-Earth import is tens of thousands of grids, so loading them all would
     * cost more heap than the server has -- and a regional world gains the same lazy behaviour.
     */
    private dev.terraforge.core.data.LandcoverProvider loadLandcoverProvider() {
        Path directory = getDataFolder().toPath().resolve(config.data().dataDirectory()).resolve("landcover");
        try {
            FileLandcoverProvider provider = FileLandcoverProvider.open(
                    directory, cacheManager, config.cache().landcoverGridCacheEntries());
            if (provider.gridCount() == 0) {
                getLogger().info(LOG_PREFIX + "Land cover: no prepared grids; using climate fallback.");
                return new dev.terraforge.core.data.ConstantLandcoverProvider(
                        dev.terraforge.core.data.LandcoverProvider.LandcoverClass.UNKNOWN);
            }
            this.landcover = provider;
            getLogger().info(LOG_PREFIX + "Land cover: " + provider.gridCount()
                    + " prepared grid(s) catalogued, up to "
                    + config.cache().landcoverGridCacheEntries() + " resident.");
            return provider;
        } catch (IOException | IllegalArgumentException exception) {
            getLogger().warning(LOG_PREFIX + "Land cover: cannot load " + directory + ": "
                    + exception.getMessage() + "; using climate fallback.");
            return new dev.terraforge.core.data.ConstantLandcoverProvider(
                    dev.terraforge.core.data.LandcoverProvider.LandcoverClass.UNKNOWN);
        }
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

    /** The terrain pipeline and its chunk sampler. */
    public TerrainStack terrain() {
        return terrain;
    }

    /** Real elevation, straight from the prepared DEM tiles. */
    public DemElevationProvider elevation() {
        return elevation;
    }

    public IntegrationStatus integrations() {
        return integrations;
    }

    /** Prepared country and region boundaries, when the operator has imported them. */
    public Optional<SqliteBoundaryIndex> boundaries() {
        return Optional.ofNullable(boundaries);
    }

    /** Live per-player debug overlay. Created on demand; idle until a player switches it on. */
    synchronized DebugOverlay debugOverlay() {
        if (debugOverlay == null) {
            debugOverlay = new DebugOverlay(this);
        }
        return debugOverlay;
    }

    /**
     * Reloads the prepared geographic database and everything derived from it.
     *
     * <p>Safe while players are online: boundaries, cities and markers are lookup data, so nothing
     * already generated changes. Terrain settings are deliberately excluded -- swapping the
     * generator at runtime would mix old and new geometry at the seam.
     *
     * @return a human-readable summary, or empty when no prepared database exists
     */
    synchronized Optional<String> reloadGeography() {
        SqliteBoundaryIndex reloaded = loadBoundaryIndex();
        if (reloaded == null) {
            return Optional.empty();
        }
        this.boundaries = reloaded;
        if (townGeography != null) {
            townGeography.useGeography(reloaded);
        }
        int published = refreshMarkers();
        return Optional.of(reloaded.countryCount() + " countries, " + reloaded.regionCount() + " regions, "
                + reloaded.cityCount() + " cities, " + published + " markers");
    }

    /** Towny geography annotation, present only when Towny and a prepared database are available. */
    Optional<SqliteTownGeoService> townGeography() {
        return Optional.ofNullable(townGeography);
    }

    /** Registry of geographic markers, published to BlueMap when it is installed. */
    public GeoMarkerService markers() {
        return markers;
    }

    /** The BlueMap bridge, present only when BlueMap is installed and enabled. */
    public Optional<TerraForgeBlueMapHook> blueMap() {
        return Optional.ofNullable(blueMap);
    }

    /**
     * Re-reads the geography into the marker registry and re-publishes it.
     *
     * <p>Safe at runtime: markers are metadata, so nothing already generated changes. This is
     * deliberately the only part of {@code /earth reload} that takes effect without a restart.
     *
     * @return the number of markers published
     */
    int refreshMarkers() {
        var bluemapConfig = config.bluemap();
        int published = GeoMarkerPopulator.populate(markers, boundaries,
                GeoMarkerPopulator.Options.defaults(
                        bluemapConfig != null && bluemapConfig.cityMarkers(),
                        bluemapConfig != null && bluemapConfig.countryLabels()));
        if (blueMap != null) {
            blueMap.refreshMarkers();
        }
        return published;
    }

    synchronized boolean startPregeneration(org.bukkit.World world, org.bukkit.command.CommandSender reporter,
                                             int centreChunkX, int centreChunkZ, int radiusChunks) {
        if (pregeneration != null) return false;
        pregeneration = new PregenerationJob(this, world, reporter, centreChunkX, centreChunkZ, radiusChunks);
        pregeneration.start();
        return true;
    }

    synchronized Optional<String> pregenerationStatus() {
        return pregeneration == null ? Optional.empty() : Optional.of(pregeneration.status());
    }

    synchronized boolean cancelPregeneration() {
        if (pregeneration == null) return false;
        pregeneration.cancel("cancelled");
        pregeneration = null;
        return true;
    }

    synchronized void clearPregeneration(PregenerationJob completed) {
        if (pregeneration == completed) pregeneration = null;
    }
}
