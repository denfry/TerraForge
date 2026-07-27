package dev.terraforge.plugin;

import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

/** Bounded, one-chunk-at-a-time world pregeneration job to keep the main server responsive. */
final class PregenerationJob {
    private final TerraForgePlugin plugin;
    private final World world;
    private final CommandSender reporter;
    private final int centreX;
    private final int centreZ;
    private final int radius;
    private final int total;
    private int next;
    private int completed;
    private BukkitTask task;

    PregenerationJob(TerraForgePlugin plugin, World world, CommandSender reporter, int centreX, int centreZ, int radius) {
        this.plugin = plugin;
        this.world = world;
        this.reporter = reporter;
        this.centreX = centreX;
        this.centreZ = centreZ;
        this.radius = radius;
        this.total = Math.multiplyExact(radius * 2 + 1, radius * 2 + 1);
    }

    void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void cancel(String reason) {
        if (task != null) task.cancel();
        reporter.sendMessage(Component.text("Pregeneration " + reason + " after " + completed + "/" + total + " chunks.", NamedTextColor.YELLOW));
    }

    String status() {
        return completed + "/" + total + " chunks around " + centreX + ", " + centreZ + " in " + world.getName();
    }

    private void tick() {
        if (next >= total && completed >= total) {
            if (task != null) task.cancel();
            reporter.sendMessage(Component.text("Pregeneration complete: " + total + " chunks.", NamedTextColor.GREEN));
            plugin.clearPregeneration(this);
            return;
        }
        if (next >= total) return;
        int width = radius * 2 + 1;
        int offsetX = next % width - radius;
        int offsetZ = next / width - radius;
        next++;
        CompletableFuture<?> load = world.getChunkAtAsync(centreX + offsetX, centreZ + offsetZ, true);
        load.whenComplete((chunk, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().warning("[TerraForge] Pregeneration failed at chunk "
                        + (centreX + offsetX) + ", " + (centreZ + offsetZ) + ": " + error.getMessage());
            }
            completed++;
            if (completed % 256 == 0) {
                reporter.sendMessage(Component.text("Pregeneration: " + status(), NamedTextColor.AQUA));
            }
        }));
    }
}
