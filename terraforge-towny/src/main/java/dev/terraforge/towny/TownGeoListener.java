package dev.terraforge.towny;

import com.palmergames.bukkit.towny.event.DeleteTownEvent;
import com.palmergames.bukkit.towny.event.NewTownEvent;
import com.palmergames.bukkit.towny.event.RenameTownEvent;
import com.palmergames.bukkit.towny.event.town.TownSetSpawnEvent;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Keeps TerraForge's annotation in step with Towny.
 *
 * <p>Every handler runs at {@code MONITOR} -- TerraForge observes, it never influences whether a
 * town is created, moved or deleted -- and defers the work to the next tick, because at event time
 * Towny has not necessarily applied the change yet. The refresh itself only snapshots the town; the
 * database write happens off the server thread.
 */
public final class TownGeoListener implements Listener {

    private final Plugin plugin;
    private final SqliteTownGeoService service;

    public TownGeoListener(Plugin plugin, SqliteTownGeoService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNewTown(NewTownEvent event) {
        annotateNextTick(event.getTown());
    }

    /** A moved spawn is a moved town: coordinates, country and elevation all change with it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSetSpawn(TownSetSpawnEvent event) {
        annotateNextTick(event.getTown());
    }

    /** The stored row carries the display name, so a rename has to be written through. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRename(RenameTownEvent event) {
        annotateNextTick(event.getTown());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDelete(DeleteTownEvent event) {
        service.forget(event.getTownUUID());
    }

    private void annotateNextTick(Town town) {
        if (town == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                service.refresh(town);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[TerraForge-Towny] Cannot annotate " + town.getName()
                        + ": " + exception.getMessage());
            }
        });
    }
}
