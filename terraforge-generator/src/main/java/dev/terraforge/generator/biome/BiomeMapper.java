package dev.terraforge.generator.biome;

import dev.terraforge.core.terrain.ClimateBiome;
import org.bukkit.block.Biome;

/**
 * Maps TerraForge's real-world biome vocabulary onto Minecraft biomes.
 *
 * <p>The single point where the core's platform-neutral {@link ClimateBiome} meets Bukkit. Keeping
 * it isolated means retargeting a new Minecraft version -- or another engine -- touches one class.
 */
public interface BiomeMapper {

    /**
     * @param climate   resolved real-world biome
     * @param elevation elevation in metres, used to pick mountain variants
     * @param latitude  latitude, used to pick frozen variants
     */
    Biome toMinecraft(ClimateBiome climate, double elevation, double latitude);
}
