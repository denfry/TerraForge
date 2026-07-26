package dev.terraforge.bluemap;

/**
 * Lifecycle of the BlueMap integration.
 *
 * <p>BlueMap is a soft dependency, checked at runtime: when it is absent the hook is never
 * instantiated and the rest of TerraForge is unaffected. No hard class references to the BlueMap API
 * may escape this module.
 *
 * <p><strong>No duplication.</strong> Towny already publishes its own town and nation markers to
 * BlueMap. TerraForge adds only what Towny cannot know: geographic labels (cities, countries) and
 * the real-world coordinates of a town.
 */
public interface TerraForgeBlueMapHook {

    /** True when the BlueMap API is present and has finished loading. */
    boolean isAvailable();

    /** Registers TerraForge's marker sets. Safe to call again after a BlueMap reload. */
    void enable();

    /** Removes TerraForge's marker sets, leaving markers owned by other plugins untouched. */
    void disable();

    /** Pushes the current contents of the marker registry to BlueMap. */
    void refreshMarkers();
}
