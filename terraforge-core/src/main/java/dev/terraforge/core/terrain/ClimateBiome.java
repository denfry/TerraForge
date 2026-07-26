package dev.terraforge.core.terrain;

/**
 * Real-world biome vocabulary of TerraForge.
 *
 * <p>Deliberately platform-neutral: the mapping to Minecraft biome keys lives in the generator
 * module, so the core never imports Bukkit. Changing the Minecraft mapping (or targeting a
 * different game entirely) touches one class, not the pipeline.
 */
public enum ClimateBiome {
    OCEAN,
    DEEP_OCEAN,
    WARM_OCEAN,
    FROZEN_OCEAN,
    LAKE,
    RIVER,
    BEACH,
    STONY_SHORE,

    DESERT,
    SEMI_DESERT,
    SAVANNA,
    GRASSLAND,
    SHRUBLAND,
    TEMPERATE_FOREST,
    TEMPERATE_RAINFOREST,
    BOREAL_FOREST,
    TROPICAL_RAINFOREST,
    TROPICAL_SEASONAL_FOREST,
    WETLAND,
    MANGROVE,

    TUNDRA,
    ALPINE,
    GLACIER,

    MOUNTAIN_FOREST,
    MOUNTAIN_MEADOW,
    BARE_ROCK;

    public boolean isWater() {
        return this == OCEAN || this == DEEP_OCEAN || this == WARM_OCEAN
                || this == FROZEN_OCEAN || this == LAKE || this == RIVER;
    }
}
