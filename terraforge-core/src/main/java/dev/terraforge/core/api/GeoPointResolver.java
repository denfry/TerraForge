package dev.terraforge.core.api;

import java.util.Optional;

/**
 * Resolves a Minecraft block position to the country that contains it.
 *
 * <p>The block coordinates are interpreted through the same projection and origin as the rest of
 * TerraForge, so a consumer plugin never has to do its own latitude/longitude conversion.
 */
public interface GeoPointResolver {

    /**
     * @param blockX block X in the TerraForge world
     * @param blockZ block Z in the TerraForge world
     * @return the country containing the point, or empty over ocean / outside the prepared data
     */
    Optional<CountryHit> countryAt(int blockX, int blockZ);
}
