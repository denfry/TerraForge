package dev.terraforge.plugin;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * Per-player action-bar overlay showing what the generator produced under the player's feet.
 *
 * <p>Opt-in and per player: each subscriber has its own repeating task on the player's scheduler,
 * which on Folia runs on the thread that owns the player and on Paper on the main thread. The task
 * ends with the subscription or when the player leaves. A debug tool that costs a tick every tick
 * regardless of whether anyone is looking is not a debug tool.
 *
 * <p>Samples come from the chunk sampler's cache, so an overlay refresh is a lookup, not a
 * regeneration.
 */
final class DebugOverlay {

    /** Four refreshes a second: fast enough to follow a walking player, cheap enough to ignore. */
    private static final long REFRESH_TICKS = 5L;

    private final TerraForgePlugin plugin;
    private final Map<UUID, ScheduledTask> subscribers = new ConcurrentHashMap<>();

    DebugOverlay(TerraForgePlugin plugin) {
        this.plugin = plugin;
    }

    /** Turns the overlay on or off for one player. Returns the state it ended up in. */
    synchronized boolean toggle(Player player) {
        UUID id = player.getUniqueId();
        ScheduledTask existing = subscribers.remove(id);
        if (existing != null) {
            existing.cancel();
            return false;
        }
        // The retired callback runs when the player leaves; the subscription goes with them.
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduled -> tick(player),
                () -> subscribers.remove(id), REFRESH_TICKS, REFRESH_TICKS);
        if (task == null) {
            return false;
        }
        subscribers.put(id, task);
        return true;
    }

    boolean isEnabledFor(Player player) {
        return subscribers.containsKey(player.getUniqueId());
    }

    synchronized void shutdown() {
        subscribers.values().forEach(ScheduledTask::cancel);
        subscribers.clear();
    }

    private void tick(Player player) {
        player.sendActionBar(Component.text(describe(player), NamedTextColor.AQUA));
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
