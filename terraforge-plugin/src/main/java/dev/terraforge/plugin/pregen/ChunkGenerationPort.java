package dev.terraforge.plugin.pregen;

import java.util.concurrent.CompletableFuture;

/**
 * Abstracts async chunk generation so {@link PregenerationController} has no Bukkit dependency and
 * can be driven in tests by controllable futures instead of a real server.
 */
public interface ChunkGenerationPort {

    /** True when the chunk already exists on disk or in memory; no generation work is needed. */
    boolean isChunkGenerated(int chunkX, int chunkZ);

    /**
     * Loads or generates the chunk asynchronously, completing with {@code true} on success. A
     * completed-exceptionally future, or one completing with {@code false}, is treated as a failure.
     */
    CompletableFuture<Boolean> loadOrGenerate(int chunkX, int chunkZ);
}
