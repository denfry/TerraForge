package dev.terraforge.plugin;

import dev.terraforge.core.config.TerraForgeConfig;
import org.bukkit.plugin.PluginManager;

/**
 * Which optional integrations are actually usable right now.
 *
 * <p>Both Towny and BlueMap are soft dependencies. A missing plugin is a normal state, never an
 * error: TerraForge logs it and carries on. The distinction between "disabled in config" and "not
 * installed" is kept so the startup banner tells an admin the truth.
 *
 * @param townyAvailable   Towny is installed and enabled in the config
 * @param blueMapAvailable BlueMap is installed and enabled in the config
 * @param townyInstalled   Towny is present on the server, regardless of configuration
 * @param blueMapInstalled BlueMap is present on the server, regardless of configuration
 */
public record IntegrationStatus(
        boolean townyAvailable,
        boolean blueMapAvailable,
        boolean townyInstalled,
        boolean blueMapInstalled) {

    private static final String TOWNY_PLUGIN = "Towny";
    private static final String BLUEMAP_PLUGIN = "BlueMap";

    public static IntegrationStatus detect(PluginManager pluginManager, TerraForgeConfig config) {
        boolean townyInstalled = pluginManager.getPlugin(TOWNY_PLUGIN) != null;
        boolean blueMapInstalled = pluginManager.getPlugin(BLUEMAP_PLUGIN) != null;
        boolean townyEnabled = config.towny() != null && config.towny().enabled();
        boolean blueMapEnabled = config.bluemap() != null && config.bluemap().enabled();
        return new IntegrationStatus(
                townyInstalled && townyEnabled,
                blueMapInstalled && blueMapEnabled,
                townyInstalled,
                blueMapInstalled);
    }

    public String townyStatus() {
        return describe(townyInstalled, townyAvailable);
    }

    public String blueMapStatus() {
        return describe(blueMapInstalled, blueMapAvailable);
    }

    private static String describe(boolean installed, boolean available) {
        if (!installed) {
            return "NOT INSTALLED (integration skipped)";
        }
        return available ? "ENABLED" : "DISABLED in terraforge.yml";
    }
}
