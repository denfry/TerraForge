package dev.terraforge.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and validates {@code terraforge.yml}.
 *
 * <p>Shared by the plugin and the CLI so both interpret the configuration identically.
 * Validation is strict and fails fast: a world generated with an invalid vertical scale is worse
 * than a server that refuses to start.
 */
public final class ConfigLoader {

    private final ObjectMapper mapper;

    public ConfigLoader() {
        this.mapper = new ObjectMapper(new YAMLFactory())
                .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

    /** Loads from a file, falling back to defaults when the file does not exist. */
    public TerraForgeConfig load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return TerraForgeConfig.defaults();
        }
        try (InputStream in = Files.newInputStream(file)) {
            return validate(read(in));
        }
    }

    public TerraForgeConfig read(InputStream in) throws IOException {
        TerraForgeConfig config = mapper.readValue(in, TerraForgeConfig.class);
        return config == null ? TerraForgeConfig.defaults() : config;
    }

    public void write(TerraForgeConfig config, Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        mapper.writeValue(file.toFile(), config);
    }

    /**
     * Rejects configurations that would produce a broken world.
     *
     * @throws ConfigException with a message aimed at a server admin, not a developer
     */
    public TerraForgeConfig validate(TerraForgeConfig config) {
        require(config.world() != null && config.world().name() != null && !config.world().name().isBlank(),
                "world.name must be set");
        require(config.scale() != null && config.scale().blocksPerKm() > 0.0,
                "scale.blocks-per-km must be greater than 0");
        require(config.earth() != null && config.earth().origin() != null,
                "earth.origin must be set");
        require(Math.abs(config.earth().origin().latitude()) <= 90.0,
                "earth.origin.latitude must be between -90 and 90");
        require(Math.abs(config.earth().origin().longitude()) <= 180.0,
                "earth.origin.longitude must be between -180 and 180");

        TerraForgeConfig.TerrainSection terrain = config.terrain();
        require(terrain != null, "terrain section must be present");
        require(terrain.minY() < terrain.maxY(), "terrain.min-y must be below terrain.max-y");
        require(terrain.seaLevel() > terrain.minY() && terrain.seaLevel() < terrain.maxY(),
                "terrain.sea-level must lie between terrain.min-y and terrain.max-y");
        require(terrain.verticalExaggeration() > 0.0, "terrain.vertical-exaggeration must be greater than 0");
        require(terrain.metersPerBlock() > 0.0, "terrain.meters-per-block must be greater than 0");
        require(terrain.bedrockThickness() >= 0, "terrain.bedrock-thickness must not be negative");

        require(config.generation() != null, "generation section must be present");
        require(config.generation().workerThreads() >= 1, "generation.worker-threads must be at least 1");

        require(config.cache() != null && config.cache().memoryLimitMb() > 0,
                "cache.memory-limit-mb must be greater than 0");
        require(config.cache().demTileCacheEntries() > 0, "cache.dem-tile-cache-entries must be greater than 0");

        // Infrastructure generation is not implemented by design; refuse to pretend otherwise.
        if (config.infrastructure() != null && config.infrastructure().anyEnabled()) {
            throw new ConfigException("infrastructure.* must all be false -- TerraForge generates "
                    + "natural terrain only; settlements are built by players");
        }

        TerraForgeConfig.TestRegionSection region = config.testRegion();
        if (region != null) {
            require(region.latitudeMin() < region.latitudeMax(),
                    "test-region.latitude-min must be below test-region.latitude-max");
            require(region.longitudeMin() < region.longitudeMax(),
                    "test-region.longitude-min must be below test-region.longitude-max");
        }
        return config;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ConfigException(message);
        }
    }

    /** Thrown when {@code terraforge.yml} is not usable. */
    public static final class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }
    }
}
