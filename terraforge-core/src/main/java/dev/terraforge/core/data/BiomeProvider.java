package dev.terraforge.core.data;

import dev.terraforge.core.terrain.ClimateBiome;

/**
 * Decides the climate biome of a point from real geodata.
 *
 * <p>The decision is driven by land cover, elevation, latitude and water state -- never by Minecraft
 * noise. Noise may only be used to dither class boundaries so that biome edges are not pixelated.
 */
public interface BiomeProvider extends DataProvider {

    ClimateBiome biomeAt(double latitude, double longitude, double elevationMeters,
                         WaterProvider.WaterType water);
}
