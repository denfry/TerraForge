package dev.terraforge.plugin.world;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cached SHA-256 fingerprint of the prepared DEM/GIS data directory, mirroring {@link
 * DemCorruptionCache}'s pattern: {@link DemDataFingerprint#of} walks the whole directory tree, so it
 * must never run on the server/command thread.
 *
 * <p>The fingerprint is computed once synchronously at construction -- during plugin startup, not in
 * response to a live command -- and after that is only ever refreshed in the background via {@link
 * #refreshAsync(Executor)}. Every live read via {@link #currentFingerprint()} returns whatever was
 * last computed, instantly, never walking the directory on the calling thread.
 */
public final class DemFingerprintCache {
    private final Path directory;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile String fingerprint;

    public DemFingerprintCache(Path directory) {
        this.directory = directory;
        this.fingerprint = DemDataFingerprint.of(directory);
    }

    /** Last computed fingerprint; instant, never walks the directory on the calling thread. */
    public String currentFingerprint() {
        return fingerprint;
    }

    /** Kicks a background recompute on {@code executor} if one is not already running. Never blocks the caller. */
    public void refreshAsync(Executor executor) {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.supplyAsync(() -> DemDataFingerprint.of(directory), executor)
                .whenComplete((value, error) -> {
                    if (error == null) {
                        fingerprint = value;
                    }
                    refreshing.set(false);
                });
    }
}
