package dev.terraforge.generator.surface;

import dev.terraforge.core.terrain.ClimateBiome;
import org.bukkit.Material;

/**
 * Which blocks a climate biome is made of.
 *
 * <p>Three layers, which is all a real-world surface needs at TerraForge's resolution: the block
 * you walk on, the few blocks under it, and stone below that. Ore and cave generation is left to
 * vanilla, which already does it well and does it underground where geography no longer applies.
 */
public final class SurfacePalette {

    /** Blocks of {@code filler} placed between the top block and the stone below. */
    public static final int FILLER_DEPTH = 3;

    private SurfacePalette() {
    }

    /**
     * @param top    the exposed surface block
     * @param filler the layer directly beneath it
     */
    public record Layers(Material top, Material filler) {
    }

    public static Layers forBiome(ClimateBiome biome) {
        return switch (biome) {
            case OCEAN, DEEP_OCEAN, WARM_OCEAN, FROZEN_OCEAN -> new Layers(Material.GRAVEL, Material.GRAVEL);
            case LAKE, RIVER -> new Layers(Material.SAND, Material.SAND);
            case BEACH -> new Layers(Material.SAND, Material.SAND);
            case STONY_SHORE -> new Layers(Material.STONE, Material.STONE);

            case DESERT -> new Layers(Material.SAND, Material.SANDSTONE);
            case SEMI_DESERT -> new Layers(Material.TERRACOTTA, Material.TERRACOTTA);
            case SAVANNA, GRASSLAND, SHRUBLAND -> new Layers(Material.GRASS_BLOCK, Material.DIRT);
            case TEMPERATE_FOREST, TEMPERATE_RAINFOREST -> new Layers(Material.GRASS_BLOCK, Material.DIRT);
            case BOREAL_FOREST -> new Layers(Material.PODZOL, Material.DIRT);
            case TROPICAL_RAINFOREST, TROPICAL_SEASONAL_FOREST -> new Layers(Material.GRASS_BLOCK, Material.DIRT);
            case WETLAND -> new Layers(Material.GRASS_BLOCK, Material.CLAY);
            case MANGROVE -> new Layers(Material.MUD, Material.MUD);

            case TUNDRA -> new Layers(Material.SNOW_BLOCK, Material.DIRT);
            case ALPINE -> new Layers(Material.SNOW_BLOCK, Material.STONE);
            case GLACIER -> new Layers(Material.PACKED_ICE, Material.PACKED_ICE);

            case MOUNTAIN_FOREST -> new Layers(Material.GRASS_BLOCK, Material.DIRT);
            case MOUNTAIN_MEADOW -> new Layers(Material.GRASS_BLOCK, Material.DIRT);
            case BARE_ROCK -> new Layers(Material.STONE, Material.STONE);
        };
    }
}
