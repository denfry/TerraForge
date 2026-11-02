package dev.terraforge.plugin.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.terraforge.core.coord.WorldExtent;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

class PlanetEdgeGuardTest {

    private static final WorldExtent PLANET = new WorldExtent(-20037, -10018, 20036, 10017);

    /** A Nether portal at z 1500 maps to z 12000, past the planet: the exit is moved onto the edge, not refused. */
    @Test
    void netherPortalPastTheEdgeComesOutInside() {
        Location exit = PlanetEdgeGuard.portalExit(PLANET, new Location(null, 800, 70, 12000, 90f, 10f));
        assertTrue(PLANET.contains(exit.getX(), exit.getZ()));
        assertEquals(800, exit.getX());
        assertEquals(70, exit.getY());
        assertEquals(90f, exit.getYaw());
    }
}
