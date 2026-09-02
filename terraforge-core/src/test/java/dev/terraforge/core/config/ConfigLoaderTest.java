package dev.terraforge.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

    private final ConfigLoader loader = new ConfigLoader();

    private TerraForgeConfig shippedConfig() throws IOException {
        try (InputStream in = ConfigLoaderTest.class.getResourceAsStream("/terraforge.yml")) {
            assertThat(in).as("shipped terraforge.yml must be on the classpath").isNotNull();
            return loader.read(in);
        }
    }

    @Test
    @DisplayName("the shipped terraforge.yml parses and validates")
    void shippedConfigIsValid() throws IOException {
        TerraForgeConfig config = loader.validate(shippedConfig());

        assertThat(config.world().name()).isEqualTo("earth");
        assertThat(config.scale().blocksPerKm()).isEqualTo(1.0);
        assertThat(config.earth().projection()).isEqualTo("equirectangular");
        assertThat(config.earth().originPoint().latitude()).isEqualTo(51.0);
        assertThat(config.terrain().seaLevel()).isEqualTo(63);
        assertThat(config.generation().naturalOnly()).isTrue();
        assertThat(config.generation().caves()).isFalse();
        // The two switches that decide whether vanilla worldgen touches a chunk at all. Both must
        // ship off: vanilla's carvers, aquifer and decorators read vanilla's vertical frame, not
        // this world's, and the shipped config is not vanilla's frame.
        assertThat(config.generation().vanillaCaves()).isFalse();
        assertThat(config.generation().vanillaDecorations()).isFalse();
        assertThat(config.generation().manMadeStructures()).isFalse();
        assertThat(config.infrastructure().anyEnabled()).isFalse();
        assertThat(config.testRegion().name()).isEqualTo("central-europe");
        assertThat(config.testRegion().toBounds().contains(50.11, 8.68)).isTrue();
    }

    @Test
    @DisplayName("the shipped file matches the built-in defaults")
    void shippedConfigMatchesDefaults() throws IOException {
        assertThat(shippedConfig()).isEqualTo(TerraForgeConfig.defaults());
    }

    @Test
    void defaultsValidate() {
        assertThat(loader.validate(TerraForgeConfig.defaults())).isNotNull();
    }

    @Test
    @DisplayName("enabling infrastructure is rejected")
    void infrastructureCannotBeEnabled() {
        TerraForgeConfig config = withInfrastructure(
                new TerraForgeConfig.InfrastructureSection(true, false, false, false, false, false));
        assertThatThrownBy(() -> loader.validate(config))
                .isInstanceOf(ConfigLoader.ConfigException.class)
                .hasMessageContaining("natural terrain only");
    }

    @Test
    void invalidVerticalScaleIsRejected() {
        TerraForgeConfig defaults = TerraForgeConfig.defaults();
        TerraForgeConfig broken = new TerraForgeConfig(
                defaults.world(), defaults.scale(), defaults.earth(),
                new TerraForgeConfig.TerrainSection(63, 320, -64, 1.0, 1.0, 0.0, 8),
                defaults.water(), defaults.biomes(), defaults.generation(), defaults.pregeneration(),
                defaults.infrastructure(), defaults.data(), defaults.cache(), defaults.towny(),
                defaults.bluemap(), defaults.debug(), defaults.testRegion());

        assertThatThrownBy(() -> loader.validate(broken))
                .isInstanceOf(ConfigLoader.ConfigException.class)
                .hasMessageContaining("min-y");
    }

    @Test
    void invalidScaleIsRejected() {
        TerraForgeConfig defaults = TerraForgeConfig.defaults();
        TerraForgeConfig broken = new TerraForgeConfig(
                defaults.world(), new TerraForgeConfig.ScaleSection(0.0), defaults.earth(),
                defaults.terrain(), defaults.water(), defaults.biomes(),
                defaults.generation(), defaults.pregeneration(), defaults.infrastructure(), defaults.data(), defaults.cache(),
                defaults.towny(), defaults.bluemap(), defaults.debug(), defaults.testRegion());

        assertThatThrownBy(() -> loader.validate(broken))
                .isInstanceOf(ConfigLoader.ConfigException.class)
                .hasMessageContaining("blocks-per-km");
    }

    @Test
    void pregenerationDefaultsAreSafeAndInvalidLimitsAreRejected() {
        assertThat(TerraForgeConfig.defaults().pregeneration())
                .isEqualTo(new TerraForgeConfig.PregenerationSection(1, true, 18.0, 40.0, 15, 10, 128));
        TerraForgeConfig d = TerraForgeConfig.defaults();
        TerraForgeConfig invalid = new TerraForgeConfig(d.world(), d.scale(), d.earth(), d.terrain(), d.water(),
                d.biomes(), d.generation(),
                new TerraForgeConfig.PregenerationSection(0, true, 18.0, 40.0, 15, 10, 128),
                d.infrastructure(), d.data(), d.cache(), d.towny(), d.bluemap(), d.debug(), d.testRegion());
        assertThatThrownBy(() -> loader.validate(invalid)).isInstanceOf(ConfigLoader.ConfigException.class)
                .hasMessageContaining("max-in-flight");
    }

    @Test
    @DisplayName("data paths cannot escape the TerraForge plugin directory")
    void parentDataPathIsRejected() {
        TerraForgeConfig defaults = TerraForgeConfig.defaults();
        TerraForgeConfig broken = withData(new TerraForgeConfig.DataSection(
                "../shared-data", defaults.data().cacheDirectory(), defaults.data().databaseFile()));

        assertThatThrownBy(() -> loader.validate(broken))
                .isInstanceOf(ConfigLoader.ConfigException.class)
                .hasMessageContaining("data.data-directory")
                .hasMessageContaining("inside the TerraForge plugin directory");
    }

    @Test
    @DisplayName("every configured data path must be present")
    void blankDatabasePathIsRejected() {
        TerraForgeConfig defaults = TerraForgeConfig.defaults();
        TerraForgeConfig broken = withData(new TerraForgeConfig.DataSection(
                defaults.data().dataDirectory(), defaults.data().cacheDirectory(), " "));

        assertThatThrownBy(() -> loader.validate(broken))
                .isInstanceOf(ConfigLoader.ConfigException.class)
                .hasMessageContaining("data.database-file must be set");
    }

    @Test
    @DisplayName("unknown keys are ignored so older configs keep working")
    void unknownKeysAreIgnored() throws IOException {
        String yaml = """
                world:
                  name: earth
                  future-option: 42
                scale:
                  blocks-per-km: 2.0
                """;
        TerraForgeConfig config = loader.read(new java.io.ByteArrayInputStream(yaml.getBytes()));
        assertThat(config.world().name()).isEqualTo("earth");
        assertThat(config.scale().blocksPerKm()).isEqualTo(2.0);
    }

    private static TerraForgeConfig withInfrastructure(TerraForgeConfig.InfrastructureSection section) {
        TerraForgeConfig d = TerraForgeConfig.defaults();
        return new TerraForgeConfig(d.world(), d.scale(), d.earth(), d.terrain(), d.water(), d.biomes(),
                d.generation(), d.pregeneration(), section, d.data(), d.cache(), d.towny(), d.bluemap(),
                d.debug(), d.testRegion());
    }

    private static TerraForgeConfig withData(TerraForgeConfig.DataSection section) {
        TerraForgeConfig d = TerraForgeConfig.defaults();
        return new TerraForgeConfig(d.world(), d.scale(), d.earth(), d.terrain(), d.water(), d.biomes(),
                d.generation(), d.pregeneration(), d.infrastructure(), section, d.cache(), d.towny(),
                d.bluemap(), d.debug(), d.testRegion());
    }
}
