package dev.terraforge.core.api;

import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.EarthLocation;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.coord.MinecraftPos;

/**
 * Public API translating between the Minecraft world and the Earth.
 *
 * <p>This is the entry point third-party plugins should use; it is registered in the Bukkit services
 * manager on enable.
 *
 * <pre>{@code
 * EarthLocation location = earthService.getLocation(x, y, z);
 * }</pre>
 */
public interface EarthService {

    /** The transformer configured for the Earth world. */
    CoordinateTransformer transformer();

    /** Geographic position of a Minecraft column. */
    GeoPoint toGeographic(double blockX, double blockZ);

    /** Minecraft position of a geographic point. */
    MinecraftPos toMinecraft(double latitude, double longitude);

    /**
     * Fully resolved location: geography, elevation, country, region and biome.
     *
     * <p>Performs cached geo lookups; call it from commands and events, not from a tight loop.
     */
    EarthLocation getLocation(double blockX, double blockY, double blockZ);

    /** Resolved location for a geographic point, with the Minecraft coordinates filled in. */
    EarthLocation getLocation(double latitude, double longitude);

    /** Real-world distance in metres between two geographic points (WGS84 geodesic). */
    double distanceMeters(GeoPoint a, GeoPoint b);

    /**
     * Suggested Y to place an entity safely at a geographic point -- the terrain surface, or the
     * water surface when the point is at sea.
     */
    int getSurfaceY(double latitude, double longitude);
}
