package dev.terraforge.plugin.pregen;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Bukkit/Paper-facing side of pregeneration: chunk I/O via {@link World}, health snapshots from
 * {@link org.bukkit.Server}, and the main-thread executor {@link PregenerationController} serializes
 * every callback onto.
 */
public final class PaperPregenerationAdapter implements ChunkGenerationPort {

    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;

    private final Plugin plugin;
    private final World world;
    private final boolean demCoverageAvailable;
    private final AtomicBoolean recentFailure = new AtomicBoolean();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final TickHealthSampler tickHealth;

    public PaperPregenerationAdapter(Plugin plugin, World world, boolean demCoverageAvailable,
                                     TickHealthSampler tickHealth) {
        this.plugin = plugin;
        this.tickHealth = tickHealth;
        this.world = world;
        this.demCoverageAvailable = demCoverageAvailable;
    }

    @Override
    public boolean isChunkGenerated(int chunkX, int chunkZ) {
        return world.isChunkGenerated(chunkX, chunkZ);
    }

    @Override
    public CompletableFuture<Boolean> loadOrGenerate(int chunkX, int chunkZ) {
        // The completion runs on the thread that owns the chunk (the main thread on Paper, the
        // region's thread on Folia), so it is where that region's health can be read.
        return world.getChunkAtAsync(chunkX, chunkZ, true).thenApply(chunk -> {
            tickHealth.sample(plugin.getServer());
            return chunk != null;
        });
    }

    /** Marks the most recent chunk callback as a failure; the next snapshot reports it once, then clears. */
    public void markRecentFailure() {
        recentFailure.set(true);
    }

    /** Set once during {@code onDisable}; every later health snapshot reports the server as shutting down. */
    public void markShuttingDown() {
        shuttingDown.set(true);
    }

    /** Reads live server health for {@link ServerHealthPolicy}. Paper's one-minute TPS and average MSPT only. */
    public ServerHealthSnapshot snapshot() {
        var server = plugin.getServer();
        double tps = tickHealth.tps(server);
        double mspt = tickHealth.mspt(server);
        int players = server.getOnlinePlayers().size();
        return new ServerHealthSnapshot(players, tps, mspt, usableDiskGb(), demCoverageAvailable,
                recentFailure.getAndSet(false), shuttingDown.get());
    }

    private long usableDiskGb() {
        File worldFolder = world.getWorldFolder();
        return worldFolder.getUsableSpace() / BYTES_PER_GB;
    }

    /**
     * The single serialization boundary {@link PregenerationController} relies on: runs directly when
     * already on the global region thread, otherwise schedules there. That thread is the main thread
     * on Paper and the one global thread on Folia, so tasks handed to this executor never run
     * concurrently with each other. {@code isPrimaryThread()} is not enough: on Folia it is true on
     * every region thread, and two regions would run callbacks at once.
     */
    public Executor mainThreadExecutor() {
        return task -> {
            if (plugin.getServer().isGlobalTickThread()) {
                task.run();
            } else {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
            }
        };
    }
}
