package dev.terraforge.plugin;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Per-player action-bar overlay showing what the generator produced under the player's feet.
 *
 * <p>Opt-in and per player: the repeating task exists only while at least one player is subscribed,
 * and it stops itself as soon as the last one leaves. A debug tool that costs a tick every tick
 * regardless of whether anyone is looking is not a debug tool.
 *
 * <p>Samples come from the chunk sampler's cache, so an overlay refresh is a lookup, not a
 * regeneration.
 */
final class DebugOverlay {

    /** Four refreshes a second: fast enough to follow a walking player, cheap enough to ignore. */
    private static final long REFRESH_TICKS = 5L;

    private final TerraForgePlugin plugin;
    private final Set<UUID> subscribers = ConcurrentHashMap.newKeySet();
    private BukkitTask task;

    DebugOverlay(TerraForgePlugin plugin) {
        this.plugin = plugin;
    }

    /** Turns the overlay on or off for one player. Returns the state it ended up in. */
    synchronized boolean toggle(Player player) {
        UUID id = player.getUniqueId();
        boolean enabled;
        if (subscribers.remove(id)) {
            enabled = false;
        } else {
            subscribers.add(id);
            enabled = true;
        }
        if (subscribers.isEmpty()) {
            stop();
        } else if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, REFRESH_TICKS, REFRESH_TICKS);
        }
        return enabled;
    }

    boolean isEnabledFor(Player player) {
        return subscribers.contains(player.getUniqueId());
    }

    synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    synchronized void shutdown() {
        subscribers.clear();
        stop();
    }

    private void tick() {
        subscribers.removeIf(id -> plugin.getServer().getPlayer(id) == null);
        if (subscribers.isEmpty()) {
            stop();
            return;
        }
        for (UUID id : subscribers) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                player.sendActionBar(Component.text(describe(player), NamedTextColor.AQUA));
            }
        }
    }

    private String describe(Player player) {
        var location = player.getLocation();
        var geographic = plugin.transformer().toGeographic(location.getX(), location.getZ());
        var minecraft = plugin.transformer().toMinecraft(geographic);
        var samples = plugin.terrain().chunkSampler().sample(minecraft.chunkX(), minecraft.chunkZ());
        var sample = samples.at(Math.floorMod(minecraft.blockX(), 16), Math.floorMod(minecraft.blockZ(), 16));
        return String.format(Locale.ROOT, "%.4f, %.4f | %.0f m | Y %d | %s | %s%s",
                geographic.latitude(), geographic.longitude(), sample.elevationMeters(), sample.surfaceY(),
                sample.biome(), sample.waterType(), sample.fromFallback() ? " | fallback DEM" : "");
    }
}
