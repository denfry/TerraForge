package dev.terraforge.generator.biome;

import dev.terraforge.core.terrain.ClimateBiome;
import org.bukkit.block.Biome;

/**
 * Default mapping from TerraForge's real-world biomes onto vanilla Minecraft biomes.
 *
 * <p>Each choice is made for what the player sees and feels -- surface blocks, vegetation, weather,
 * mob spawns -- not for the name. Boreal forest is taiga because taiga <em>looks</em> like Finland;
 * above the Arctic Circle it becomes snowy taiga because Finland north of it has snow.
 *
 * <p>Latitude and elevation pick the variant, so one climate biome can produce several Minecraft
 * biomes: the same tundra is snowy plains in Lapland and ice spikes on the Greenland plateau.
 */
public final class ClimateBiomeMapper implements BiomeMapper {

    private static final double ARCTIC = 60.0;
    private static final double POLAR = 66.5;
    /** Above this elevation a glacier is a peak rather than an ice sheet, in metres. */
    private static final double PEAK_GLACIER = 2000.0;

    @Override
    public Biome toMinecraft(ClimateBiome climate, double elevation, double latitude) {
        double absLatitude = Math.abs(latitude);
        return switch (climate) {
            case OCEAN -> absLatitude >= ARCTIC ? Biome.COLD_OCEAN : Biome.OCEAN;
            case DEEP_OCEAN -> absLatitude >= ARCTIC ? Biome.DEEP_COLD_OCEAN : Biome.DEEP_OCEAN;
            case WARM_OCEAN -> Biome.WARM_OCEAN;
            case FROZEN_OCEAN -> Biome.FROZEN_OCEAN;
            case LAKE, RIVER -> absLatitude >= POLAR ? Biome.FROZEN_RIVER : Biome.RIVER;
            case BEACH -> absLatitude >= ARCTIC ? Biome.SNOWY_BEACH : Biome.BEACH;
            case STONY_SHORE -> Biome.STONY_SHORE;

            case DESERT -> Biome.DESERT;
            case SEMI_DESERT -> Biome.BADLANDS;
            case SAVANNA -> Biome.SAVANNA;
            case GRASSLAND -> absLatitude >= POLAR ? Biome.SNOWY_PLAINS : Biome.PLAINS;
            case SHRUBLAND -> absLatitude < 35.0 ? Biome.SPARSE_JUNGLE : Biome.PLAINS;
            case TEMPERATE_FOREST -> Biome.FOREST;
            case TEMPERATE_RAINFOREST -> Biome.DARK_FOREST;
            case BOREAL_FOREST -> absLatitude >= ARCTIC ? Biome.SNOWY_TAIGA : Biome.TAIGA;
            case TROPICAL_RAINFOREST -> Biome.JUNGLE;
            case TROPICAL_SEASONAL_FOREST -> Biome.SPARSE_JUNGLE;
            case WETLAND -> Biome.SWAMP;
            case MANGROVE -> Biome.MANGROVE_SWAMP;

            case TUNDRA -> Biome.SNOWY_PLAINS;
            case ALPINE -> Biome.SNOWY_SLOPES;
            // Ice Spikes is deliberately avoided: vanilla decorates it with its own noise-driven
            // "ice_spike" feature, an erratic forest of packed-ice columns that has no relationship
            // to real glacier surfaces and reads as visual noise instead of terrain.
            case GLACIER -> elevation > PEAK_GLACIER ? Biome.FROZEN_PEAKS : Biome.SNOWY_SLOPES;

            case MOUNTAIN_FOREST -> Biome.GROVE;
            case MOUNTAIN_MEADOW -> Biome.MEADOW;
            case BARE_ROCK -> elevation > PEAK_GLACIER ? Biome.JAGGED_PEAKS : Biome.STONY_PEAKS;
        };
    }
}
