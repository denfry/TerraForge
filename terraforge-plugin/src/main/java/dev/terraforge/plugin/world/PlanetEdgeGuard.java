package dev.terraforge.plugin.world;

import dev.terraforge.core.coord.WorldExtent;
import dev.terraforge.generator.TerraForgeChunkGenerator;
import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.event.entity.EntityMoveEvent;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
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
 *
 * <p>With {@code world.nether.border} the Nether paired with that world ({@code <name>_nether}) is held
 * the same way to the planet's edge scaled by the two dimensions' coordinate ratio, 1:8. Without it the
 * Nether is endless, a player walks past where the planet ends, and every portal back comes out at the
 * edge, where they pile up.
 */
public final class PlanetEdgeGuard implements Listener {

    /** How far inside the edge a stopped player is put back, so they are not pinned against it. */
    private static final double INSET_BLOCKS = 0.5;

    private final Logger logger;
    private final String logPrefix;
    private final boolean netherBorder;
    /** Each world's edge, or none; looked up once, as moves ask for it many times a second. */
    private final java.util.Map<java.util.UUID, java.util.Optional<WorldExtent>> extents =
            new java.util.concurrent.ConcurrentHashMap<>();

    public PlanetEdgeGuard(Logger logger, String logPrefix) {
        this(logger, logPrefix, false);
    }

    /** @param netherBorder also hold the paired Nether to the planet's edge, scaled */
    public PlanetEdgeGuard(Logger logger, String logPrefix, boolean netherBorder) {
        this.logger = logger;
        this.logPrefix = logPrefix;
        this.netherBorder = netherBorder;
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

    /**
     * A teleport past the edge is refused, except through a portal: the Nether is scaled 1:8 and its
     * far side maps past the planet, and refusing the portal would strand the player in the Nether.
     * The portal exit is moved onto the edge instead; Paper searches for or builds the portal there.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        WorldExtent extent = extentOf(to.getWorld());
        if (extent == null || extent.contains(to.getX(), to.getZ())) {
            return;
        }
        if (event instanceof PlayerPortalEvent) {
            event.setTo(portalExit(extent, to));
        } else {
            event.setCancelled(true);
        }
    }

    /** Where a portal that would lead past the edge comes out: the nearest point inside. */
    static Location portalExit(WorldExtent extent, Location to) {
        return inside(extent, to);
    }

    /**
     * Living mounts -- horses, pigs, camels, striders, happy ghasts -- move by {@link EntityMoveEvent};
     * {@link VehicleMoveEvent} only covers boats and minecarts, and a ridden player sends no move.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMountMove(EntityMoveEvent event) {
        Location to = event.getTo();
        WorldExtent extent = extentOf(to.getWorld());
        if (extent == null || extent.contains(to.getX(), to.getZ()) || !carriesPlayer(event.getEntity())) {
            return;
        }
        if (extent.contains(event.getFrom().getX(), event.getFrom().getZ())) {
            event.setCancelled(true);
        } else {
            event.getEntity().setVelocity(new Vector());
            // Folia refuses a synchronous teleport; the async one is a plain teleport on Paper.
            event.getEntity().teleportAsync(inside(extent, to), PlayerTeleportEvent.TeleportCause.PLUGIN,
                    TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
    }

    private static boolean carriesPlayer(Entity mount) {
        for (Entity passenger : mount.getPassengers()) {
            if (passenger instanceof Player) {
                return true;
            }
        }
        return false;
    }

    /** A boat or a minecart carries its rider past {@link PlayerMoveEvent}, which only sees walking. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleMove(VehicleMoveEvent event) {
        Location to = event.getTo();
        WorldExtent extent = extentOf(to.getWorld());
        if (extent == null || extent.contains(to.getX(), to.getZ())) {
            return;
        }
        Vehicle vehicle = event.getVehicle();
        vehicle.setVelocity(new Vector());
        vehicle.teleportAsync(inside(extent, to), PlayerTeleportEvent.TeleportCause.PLUGIN,
                TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    private static Location inside(WorldExtent extent, Location location) {
        Location clamped = location.clone();
        clamped.setX(extent.clampX(location.getX(), INSET_BLOCKS));
        clamped.setZ(extent.clampZ(location.getZ(), INSET_BLOCKS));
        return clamped;
    }

    private WorldExtent extentOf(World world) {
        if (world == null) {
            return null;
        }
        if (world.getGenerator() instanceof TerraForgeChunkGenerator generator) {
            return generator.extent();
        }
        if (!netherBorder || world.getEnvironment() != World.Environment.NETHER) {
            return null;
        }
        java.util.Optional<WorldExtent> known = extents.get(world.getUID());
        if (known != null) {
            return known.orElse(null);
        }
        WorldExtent extent = netherExtent(world);
        // Remembered once the overworld is there to ask; until then, asked again next time.
        if (extent != null || org.bukkit.Bukkit.getWorld(stripNether(world.getName())) != null) {
            extents.put(world.getUID(), java.util.Optional.ofNullable(extent));
        }
        return extent;
    }

    private static String stripNether(String name) {
        return name.endsWith("_nether") ? name.substring(0, name.length() - "_nether".length()) : name;
    }

    /** The planet's edge in the Nether named after a TerraForge world, or {@code null}. */
    private static WorldExtent netherExtent(World nether) {
        String name = nether.getName();
        if (!name.endsWith("_nether")) {
            return null;
        }
        World overworld = org.bukkit.Bukkit.getWorld(name.substring(0, name.length() - "_nether".length()));
        if (overworld == null || !(overworld.getGenerator() instanceof TerraForgeChunkGenerator generator)
                || generator.extent() == null) {
            return null;
        }
        return generator.extent().scaled(overworld.getCoordinateScale() / nether.getCoordinateScale());
    }
}
