package dev.terraforge.core.terrain;

import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.core.data.WaterProvider.WaterType;

/**
 * Everything the generator knows about one horizontal position, resolved once per column.
 *
 * <p>Sampling happens at column granularity, never per block: a 16x16 chunk costs 256 samples, not
 * 256 * 384.
 *
 * @param elevationMeters real elevation above sea level (may be negative)
 * @param surfaceY        Minecraft Y of the terrain surface after vertical scaling
 * @param waterType       water classification at this column
 * @param waterSurfaceY   Minecraft Y of the water surface; only meaningful when water is present
 * @param landcover       raw land cover class
 * @param biome           resolved climate biome
 * @param fromFallback    true when any input was substituted because data was missing
 */
public record TerrainSample(
        double elevationMeters,
        int surfaceY,
        WaterType waterType,
        int waterSurfaceY,
        LandcoverClass landcover,
        ClimateBiome biome,
        boolean fromFallback) {

    public boolean isWater() {
        return waterType.isWater();
    }

    public boolean isUnderwater() {
        return waterType.isWater() && surfaceY < waterSurfaceY;
    }
}
