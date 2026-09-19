package dev.terraforge.plugin.world;

import dev.terraforge.core.coord.WorldExtent;
import dev.terraforge.generator.TerraForgeChunkGenerator;
import io.papermc.paper.entity.TeleportFlag;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.util.Vector;

/**
 * Holds players inside the planet when {@code world.border.enabled} is on.
 *
 * <p>Vanilla's world border is a square and the planet is not -- an equirectangular Earth is twice
 * as wide as it is tall -- so the vanilla border is drawn around the longer axis for the wall players
 * can see, and this listener enforces the shorter one. The edge comes from each world's own
 * generator, so a world without a TerraForge border is never touched.
 */
public final class PlanetEdgeGuard implements Listener {

    /** How far inside the edge a stopped player is put back, so they are not pinned against it. */
    private static final double INSET_BLOCKS = 0.5;

    private final Logger logger;
    private final String logPrefix;

    public PlanetEdgeGuard(Logger logger, String logPrefix) {
        this.logger = logger;
        this.logPrefix = logPrefix;
    }

    /** Draws the vanilla border around the planet's longer axis. Stored by the server in level.dat. */
    public void drawBorder(World world) {
        WorldExtent extent = extentOf(world);
        if (extent == null) {
            return;
        }
        world.getWorldBorder().setCenter(extent.centerX(), extent.centerZ());
        world.getWorldBorder().setSize(Math.max(extent.width(), extent.depth()));
        logger.info(logPrefix + "World '" + world.getName() + "' ends at the edge of the planet: x "
                + extent.minX() + ".." + extent.maxX() + ", z " + extent.minZ() + ".." + extent.maxZ());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        drawBorder(event.getWorld());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        WorldExtent extent = extentOf(to.getWorld());
        if (extent == null || extent.contains(to.getX(), to.getZ())) {
            return;
        }
        event.setTo(inside(extent, to));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        WorldExtent extent = extentOf(to.getWorld());
        if (extent != null && !extent.contains(to.getX(), to.getZ())) {
            event.setCancelled(true);
        }
    }

    /** A boat or a horse carries its rider past {@link PlayerMoveEvent}, which only sees walking. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleMove(VehicleMoveEvent event) {
        Location to = event.getTo();
        WorldExtent extent = extentOf(to.getWorld());
        if (extent == null || extent.contains(to.getX(), to.getZ())) {
            return;
        }
        Vehicle vehicle = event.getVehicle();
        vehicle.setVelocity(new Vector());
        vehicle.teleport(inside(extent, to), TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    private static Location inside(WorldExtent extent, Location location) {
        Location clamped = location.clone();
        clamped.setX(extent.clampX(location.getX(), INSET_BLOCKS));
        clamped.setZ(extent.clampZ(location.getZ(), INSET_BLOCKS));
        return clamped;
    }

    private static WorldExtent extentOf(World world) {
        return world != null && world.getGenerator() instanceof TerraForgeChunkGenerator generator
                ? generator.extent()
                : null;
    }
}
