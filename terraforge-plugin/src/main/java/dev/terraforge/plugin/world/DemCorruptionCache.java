package dev.terraforge.plugin.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/**
 * Cached count of {@code .tfdem} files present on disk but not part of {@link
 * dev.terraforge.geo.dem.FileDemReader}'s catalogue -- files it skipped as unreadable or
 * misnamed when it opened the directory.
 *
 * <p>{@code /earth data status} must answer instantly on the server command thread, so this class
 * never walks the directory synchronously from {@link #corruptFileCount()}: the count starts at
 * zero and is only ever updated by a background scan kicked off via {@link #refreshAsync(Executor)},
 * which swaps in a fresh count once it completes. A directory read failure mid-scan keeps the
 * previous, last-known-good count rather than reporting a false zero.
 */
public final class DemCorruptionCache {
    private final Path directory;
    private final IntSupplier catalogedTileCount;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile int corruptFileCount;

    public DemCorruptionCache(Path directory, IntSupplier catalogedTileCount) {
        this.directory = directory;
        this.catalogedTileCount = catalogedTileCount;
    }

    /** Last computed count; zero until the first successful background scan completes. */
    public int corruptFileCount() {
        return corruptFileCount;
    }

    /** Kicks a background scan on {@code executor} if one is not already running. Never blocks the caller. */
    public void refreshAsync(Executor executor) {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.supplyAsync(this::scan, executor).whenComplete((count, error) -> {
            if (error == null) {
                corruptFileCount = count;
            }
            refreshing.set(false);
        });
    }

    private int scan() {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (var files = Files.list(directory)) {
            long tfdemFiles = files.filter(path -> path.getFileName().toString().endsWith(".tfdem")).count();
            return (int) Math.max(0, tfdemFiles - catalogedTileCount.getAsInt());
        } catch (IOException exception) {
            return corruptFileCount;
        }
    }
}
