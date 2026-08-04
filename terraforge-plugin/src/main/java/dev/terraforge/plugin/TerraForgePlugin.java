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
import dev.terraforge.geo.water.SqliteWaterProvider;
import dev.terraforge.generator.TerraForgeChunkGenerator;
import dev.terraforge.generator.TerrainStack;
import dev.terraforge.core.coord.MinecraftPos;
import dev.terraforge.plugin.command.DataCommandContext;
import dev.terraforge.plugin.command.DataCommandHandler;
import dev.terraforge.plugin.command.DoctorCommandContext;
import dev.terraforge.plugin.command.DoctorCommandHandler;
import dev.terraforge.plugin.command.EarthCommandRouter;
import dev.terraforge.plugin.command.PaperEarthCommand;
import dev.terraforge.plugin.command.PerformanceCommandContext;
import dev.terraforge.plugin.command.PerformanceCommandHandler;
import dev.terraforge.plugin.command.PregenerationCommandContext;
import dev.terraforge.plugin.command.PregenerationCommandHandler;
import dev.terraforge.plugin.command.WorldCommandContext;
import dev.terraforge.plugin.command.WorldCommandHandler;
import dev.terraforge.plugin.pregen.PaperPregenerationAdapter;
import dev.terraforge.plugin.pregen.PregenerationCheckpoint;
import dev.terraforge.plugin.pregen.PregenerationCheckpointStore;
import dev.terraforge.plugin.pregen.PregenerationController;
import dev.terraforge.plugin.pregen.PregenerationSpec;
import dev.terraforge.plugin.pregen.ServerHealthPolicy;
import dev.terraforge.plugin.pregen.ServerHealthSnapshot;
import dev.terraforge.plugin.world.BootstrapDatapackService;
import dev.terraforge.plugin.world.BukkitManagedWorldEnvironment;
import dev.terraforge.plugin.world.DemCorruptionCache;
import dev.terraforge.plugin.world.DemDataFingerprint;
import dev.terraforge.plugin.world.DemFingerprintCache;
import dev.terraforge.plugin.world.LiveWorldSnapshot;
import dev.terraforge.plugin.world.LiveWorldVerifier;
import dev.terraforge.plugin.world.ManagedWorldEnvironment;
import dev.terraforge.plugin.world.ManagedWorldManifest;
import dev.terraforge.plugin.world.ManagedWorldManifestStore;
import dev.terraforge.plugin.world.ManagedWorldService;
import dev.terraforge.plugin.world.ManagedWorldStartupVerifier;
import dev.terraforge.plugin.world.PaperWorldSettingsEditor;
import dev.terraforge.plugin.world.WorldCreationCheck;
import dev.terraforge.towny.SqliteTownGeoService;
import dev.terraforge.towny.TownGeoListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper entry point.
 *
 * <p>Responsibilities are deliberately narrow: load and validate configuration, build the
 * coordinate system, detect optional integrations and wire the services together. All geography and
 * terrain work happens in the core, geo and generator modules -- this class must stay free of GIS
 * logic.
 */
public final class TerraForgePlugin extends JavaPlugin implements Listener {

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
    private dev.terraforge.core.data.WaterProvider water;
    private TerrainStack terrain;
    private SqliteBoundaryIndex boundaries;
    private SqliteTownGeoService townGeography;
    private PaperPregenerationAdapter pregenerationAdapter;
    private PregenerationController pregenerationController;
    private IntegrationStatus integrations;
    private InMemoryGeoMarkerService markers;
    private TerraForgeBlueMapHook blueMap;
    private DebugOverlay debugOverlay;
    private boolean managedWorldReady;
    private DemCorruptionCache demCorruptionCache;
    private DemFingerprintCache demFingerprintCache;
    private org.bukkit.scheduler.BukkitTask pregenerationHealthCheckTask;
    private final Executor asyncExecutor = task -> getServer().getScheduler().runTaskAsynchronously(this, task);

    /** How often {@link PregenerationController#reevaluateHealth()} is polled on a repeating task, so
     *  an AUTO_PAUSED job (including one stuck waiting out the health policy's stability window) is
     *  re-checked and automatically resumed once conditions recover, rather than staying paused forever
     *  until an operator happens to call resume() again. */
    private static final long PREGENERATION_HEALTH_RECHECK_INTERVAL_TICKS = 100L;

    /** The full name Paper registers the bootstrap-discovered height pack under: plugin name + id. */
    private static final String MANAGED_DATAPACK_NAME = "TerraForge/" + BootstrapDatapackService.DATAPACK_ID;

    /**
     * Paper chunk settings applied to the managed earth world's {@code paper-world.yml}. Not yet
     * exposed in {@code terraforge.yml} -- these match Paper's own defaults for auto-save interval and
     * per-tick auto-save chunk cap, with a short unload delay so a player crossing a chunk border does
     * not thrash it.
     */
    private static final PaperWorldSettingsEditor.ChunkSettings MANAGED_WORLD_CHUNK_SETTINGS =
            new PaperWorldSettingsEditor.ChunkSettings(6000, 24, "10s");

    /**
     * Runs under {@code load: STARTUP} (see {@code plugin.yml}), so this fires before Bukkit
     * resolves the default world's generator -- required for {@link #getDefaultWorldGenerator} to
     * ever be asked instead of Bukkit silently falling back to vanilla terrain. Everything that
     * depends on other plugins or worlds being ready is deferred to {@link #onServerLoad}, since a
     * STARTUP plugin enables before normal (POSTWORLD) plugins such as Towny or BlueMap do.
     */
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
        getServer().getPluginManager().registerEvents(this, this);
        // Lifecycle event registration (Paper's command API) closes once world loading starts, so this
        // cannot wait for ServerLoadEvent -- unlike the rest of onServerLoad, it depends on nothing
        // beyond this plugin instance.
        registerCommand("earth", "TerraForge geographic and Earth-world commands", List.of("tf", "terraforge"),
                new PaperEarthCommand(new EarthCommandRouter(new EarthCommand(this),
                        new WorldCommandHandler(new PluginWorldCommandContext()),
                        new PregenerationCommandHandler(new PluginPregenerationCommandContext()),
                        new DataCommandHandler(new PluginDataCommandContext()),
                        new DoctorCommandHandler(new PluginDoctorCommandContext()),
                        new PerformanceCommandHandler(new PluginPerformanceCommandContext()))));
    }

    /** Fires once, after every plugin has enabled and every world named in server.properties has loaded. */
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        this.integrations = IntegrationStatus.detect(getServer().getPluginManager(), config);
        initializeBoundaryServices();
        initializeMarkers();
        initializeTownyIntegration();
        initializeBlueMapIntegration();
        initializeMetrics();
        verifyManagedWorldOnStartup();
        initializePregeneration();
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
        this.demCorruptionCache = new DemCorruptionCache(demReader.directory(), this::preparedTileCount);
        this.demFingerprintCache = new DemFingerprintCache(demReader.directory());

        this.terrain = TerrainStack.create(config, transformer, verticalScale, elevation, cacheManager,
                loadWaterProvider(), loadLandcoverProvider(), loadKarstProvider());
        this.boundaries = loadBoundaryIndex();
    }

    @Override
    public void onDisable() {
        if (pregenerationHealthCheckTask != null) {
            pregenerationHealthCheckTask.cancel();
            pregenerationHealthCheckTask = null;
        }
        if (pregenerationAdapter != null) {
            pregenerationAdapter.markShuttingDown();
        }
        if (pregenerationController != null) {
            // Stops dispatching new chunks and writes one final checkpoint; does not wait for
            // whatever chunk futures are still outstanding.
            pregenerationController.shutdown();
            pregenerationController = null;
        }
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
        if (water instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                getLogger().warning(LOG_PREFIX + "Water: cannot close prepared database: " + exception.getMessage());
            }
            water = null;
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
     * Builds the bounded pregeneration controller for the primary managed Earth world.
     *
     * <p>Gated on {@link #managedWorldReady} exactly like every other managed operation: a world
     * that failed startup verification must not have chunks generated against it. Any checkpoint on
     * disk is adopted as-is -- {@link PregenerationCheckpointStore#load()} already downgrades a
     * checkpoint left {@code RUNNING} or {@code AUTO_PAUSED} by a previous run to {@code PAUSED}, so
     * pregeneration never silently resumes after a restart.
     */
    private void initializePregeneration() {
        if (!managedWorldReady) {
            return;
        }
        org.bukkit.World world = getServer().getWorld(config.world().name());
        if (world == null) {
            getLogger().warning(LOG_PREFIX + "Managed Earth world is not loaded; pregeneration stays disabled.");
            return;
        }
        var adapter = new PaperPregenerationAdapter(this, world, hasPreparedDem());
        var pregenerationConfig = config.pregeneration();
        var healthPolicy = new ServerHealthPolicy(pregenerationConfig.pauseWhenPlayersOnline(),
                pregenerationConfig.minimumTps(), pregenerationConfig.maximumMspt(),
                pregenerationConfig.minimumFreeDiskGb(), pregenerationConfig.stableResumeSeconds());
        var store = new PregenerationCheckpointStore(getDataFolder().toPath());
        PregenerationCheckpoint loaded;
        try {
            loaded = store.load().orElse(null);
        } catch (IOException exception) {
            getLogger().warning(LOG_PREFIX + "Cannot load pregeneration checkpoint: " + exception.getMessage()
                    + "; pregeneration stays disabled until it is fixed or removed.");
            return;
        }
        this.pregenerationAdapter = adapter;
        this.pregenerationController = new PregenerationController(adapter, healthPolicy, adapter::snapshot,
                store, adapter.mainThreadExecutor(), Clock.systemUTC(), pregenerationConfig.maxInFlight(),
                pregenerationConfig.checkpointEveryChunks(), loaded, getLogger());
        // Documented "automatic pause/resume" behaviour requires something to periodically re-check
        // health for an AUTO_PAUSED job: pump() otherwise only re-enters via resume() or a completion
        // callback (which early-returns once paused), so a job that auto-paused -- including one that
        // landed back in the health policy's stability window right after a manual resume() -- would
        // never be re-evaluated and would stay paused forever.
        this.pregenerationHealthCheckTask = getServer().getScheduler().runTaskTimer(this,
                pregenerationController::reevaluateHealth,
                PREGENERATION_HEALTH_RECHECK_INTERVAL_TICKS, PREGENERATION_HEALTH_RECHECK_INTERVAL_TICKS);
    }

    /** The bounded pregeneration controller, present once the managed Earth world is verified and loaded. */
    public Optional<PregenerationController> pregenerationController() {
        return Optional.ofNullable(pregenerationController);
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
        // Never walks the DEM directory on this (command/startup) thread -- reads whatever the cache
        // last computed and kicks a background recompute for the next call to pick up.
        demFingerprintCache.refreshAsync(asyncExecutor);
        String dataFingerprint = demFingerprintCache.currentFingerprint();
        return new LiveWorldSnapshot(world.getName(), primary, terraForgeGenerator, world.getMinHeight(),
                world.getMaxHeight(), datapackEnabled, configFingerprint, dataFingerprint,
                manifest.datapackFingerprint());
    }

    /** True once the managed Earth world has been verified against the live server this run. */
    public boolean managedWorldReady() {
        return managedWorldReady;
    }

    /** Whether {@link #demReader} has at least one prepared tile catalogued. */
    private boolean hasPreparedDem() {
        for (var ignored : demReader.availableTiles()) {
            return true;
        }
        return false;
    }

    /** Wires {@code WorldCommandHandler} to this plugin's live configuration and services. */
    private final class PluginWorldCommandContext implements WorldCommandContext {
        private final ManagedWorldService service = new ManagedWorldService(getDataFolder().toPath());

        @Override public ManagedWorldService service() { return service; }
        @Override public ManagedWorldEnvironment environment() { return new BukkitManagedWorldEnvironment(getServer(), hasPreparedDem()); }
        @Override public String configuredWorldName() { return config.world().name(); }
        @Override public long minimumFreeDiskGb() { return config.pregeneration().minimumFreeDiskGb(); }
        @Override public VerticalProfile verticalProfile() { return VerticalProfile.from(config.terrain()); }
        @Override public Path demDirectory() { return demReader.directory(); }
        @Override public PaperWorldSettingsEditor.ChunkSettings chunkSettings() { return MANAGED_WORLD_CHUNK_SETTINGS; }
        @Override public Path serverRoot() { return environment().serverRoot(); }
        @Override public Path worldContainer() { return environment().worldContainer(); }
        @Override public Function<ManagedWorldManifest, LiveWorldSnapshot> liveSnapshotFactory() {
            return TerraForgePlugin.this::captureLiveWorldSnapshot;
        }

        @Override public void onVerifyResult(boolean ready) {
            // Reflect an on-demand verify()'s verdict immediately: otherwise a world just marked
            // INVALID would still read as ready until the next restart, letting pregeneration keep
            // running against it.
            managedWorldReady = ready;
        }
    }

    /** Wires {@code PregenerationCommandHandler} to this plugin's live pregeneration state. */
    private final class PluginPregenerationCommandContext implements PregenerationCommandContext {
        @Override public boolean managedWorldReady() { return managedWorldReady; }
        @Override public Optional<PregenerationController> controller() { return pregenerationController(); }
        @Override public PregenerationSpec fullRegionSpec() { return TerraForgePlugin.this.fullRegionSpec(); }
        @Override public String currentConfigFingerprint() { return VerticalProfile.from(config.terrain()).fingerprint(); }
        @Override public String currentDataFingerprint() {
            // Task 14's security review forbids a server-thread GIS/DEM directory walk from command
            // handling; read the cached fingerprint and kick a background recompute instead.
            demFingerprintCache.refreshAsync(asyncExecutor);
            return demFingerprintCache.currentFingerprint();
        }
        @Override public ServerHealthSnapshot currentHealth() {
            return pregenerationAdapter != null ? pregenerationAdapter.snapshot()
                    : new ServerHealthSnapshot(getServer().getOnlinePlayers().size(), getServer().getTPS()[0],
                            getServer().getAverageTickTime(), 0, false, false, false);
        }
        @Override public long reserveDiskGb() { return config.pregeneration().minimumFreeDiskGb(); }
        @Override public boolean pauseWhenPlayersOnline() { return config.pregeneration().pauseWhenPlayersOnline(); }
        @Override public Clock clock() { return Clock.systemUTC(); }
    }

    /** Wires {@code DataCommandHandler} to the live DEM inventory. */
    private final class PluginDataCommandContext implements DataCommandContext {
        @Override public int tileCount() { return preparedTileCount(); }
        @Override public String coverageDescription() { return elevation.coverage().toString(); }
        @Override public boolean hasBathymetry() { return elevation.hasBathymetry(); }
        @Override public int missingRequestedTileCount() { return elevation.missingTiles().size(); }
        @Override public int cachedCorruptFileCount() { return demCorruptionCache.corruptFileCount(); }
        @Override public void refreshCorruptionAsync() { demCorruptionCache.refreshAsync(asyncExecutor); }
    }

    /** Wires {@code DoctorCommandHandler} to a fresh set of live diagnostics. */
    private final class PluginDoctorCommandContext implements DoctorCommandContext {
        @Override public List<WorldCreationCheck> diagnose() {
            List<WorldCreationCheck> checks = new ArrayList<>();
            checks.add(new WorldCreationCheck("managed-world", managedWorldReady,
                    managedWorldReady ? "verified against the live server"
                            : "not verified; managed operations stay disabled"));
            checks.add(new WorldCreationCheck("dem-data", preparedTileCount() > 0,
                    preparedTileCount() + " prepared tile(s)"));
            checks.add(new WorldCreationCheck("boundaries", boundaries != null,
                    boundaries != null ? "geography database loaded" : "no prepared geography database"));
            checks.add(new WorldCreationCheck("pregeneration", pregenerationController != null,
                    pregenerationController != null ? "controller active" : "controller not active"));
            return checks;
        }
    }

    /** Wires {@code PerformanceCommandHandler} to live server and pregeneration health. */
    private final class PluginPerformanceCommandContext implements PerformanceCommandContext {
        @Override public double tps() { return getServer().getTPS()[0]; }
        @Override public double mspt() { return getServer().getAverageTickTime(); }
        @Override public int onlinePlayers() { return getServer().getOnlinePlayers().size(); }
        @Override public long usableDiskGb() { return getDataFolder().getUsableSpace() / (1024L * 1024L * 1024L); }
        @Override public Optional<Integer> pregenerationInFlight() {
            return pregenerationController().map(PregenerationController::inFlightCount);
        }
        @Override public Optional<String> pregenerationState() {
            return pregenerationController().flatMap(PregenerationController::status)
                    .map(checkpoint -> checkpoint.state().toString());
        }
    }

    /** Prepared tile count from {@link #demReader}'s in-memory catalogue -- never a disk scan. */
    private int preparedTileCount() {
        int count = 0;
        for (var ignored : demReader.availableTiles()) {
            count++;
        }
        return count;
    }

    /**
     * The bounds {@code /earth pregenerate full confirm} runs against: a square centered on the
     * configured test region, sized to its longer axis so the whole region is covered.
     */
    private PregenerationSpec fullRegionSpec() {
        var bounds = config.testRegion().toBounds();
        MinecraftPos northWest = transformer.toMinecraft(bounds.maxLatitude(), bounds.minLongitude());
        MinecraftPos southEast = transformer.toMinecraft(bounds.minLatitude(), bounds.maxLongitude());
        int centerX = (int) Math.round((northWest.x() + southEast.x()) / 2.0);
        int centerZ = (int) Math.round((northWest.z() + southEast.z()) / 2.0);
        int halfWidth = (int) Math.round(Math.abs(southEast.x() - northWest.x()) / 2.0);
        int halfHeight = (int) Math.round(Math.abs(southEast.z() - northWest.z()) / 2.0);
        int radius = Math.max(halfWidth, halfHeight);
        return PregenerationSpec.around(centerX, centerZ, radius);
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
            dev.terraforge.core.data.WaterProvider provider = SqliteWaterProvider.load(
                    database, cacheManager, config.cache().waterFeatureCacheEntries());
            if (provider != null) {
                this.water = provider;
                getLogger().info(LOG_PREFIX + "Water: prepared natural water features catalogued; "
                        + "geometry decodes on demand.");
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
}
