package dev.terraforge.plugin.pregen;

import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Bounded, durable controller for one pregeneration job.
 *
 * <p>Every mutation of the job's in-memory state -- dispatch, completion handling, and the
 * pause/resume/cancel admin operations -- runs as a task submitted to a single {@code
 * callbackExecutor}. That executor is the one serialization boundary this class relies on: as long
 * as it runs submitted tasks one at a time (a single Bukkit-main-thread executor in production, a
 * direct or single-thread executor in tests), dispatch can never race a completion callback or an
 * admin call, regardless of which thread invoked them or how many chunk futures are outstanding.
 *
 * <p>{@link #status()} and {@link #inFlightCount()} are the only members read off the executor
 * thread; the checkpoint reference is {@code volatile} and the in-flight counter is atomic so those
 * reads are safe without needing the executor themselves.
 */
public final class PregenerationController {

    private final ChunkGenerationPort port;
    private final ServerHealthPolicy healthPolicy;
    private final Supplier<ServerHealthSnapshot> snapshotSupplier;
    private final PregenerationCheckpointStore store;
    private final Executor callbackExecutor;
    private final Clock clock;
    private final int maxInFlight;
    private final int checkpointEveryChunks;
    private final Logger logger;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicBoolean stopped = new AtomicBoolean();

    /** Confined to {@code callbackExecutor}; not read there only via the volatile publication below. */
    private volatile PregenerationCheckpoint checkpoint;
    private long terminalSinceCheckpoint;

    public PregenerationController(ChunkGenerationPort port, ServerHealthPolicy healthPolicy,
            Supplier<ServerHealthSnapshot> snapshotSupplier, PregenerationCheckpointStore store,
            Executor callbackExecutor, Clock clock, int maxInFlight, int checkpointEveryChunks,
            PregenerationCheckpoint initialCheckpoint, Logger logger) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be at least one");
        }
        if (checkpointEveryChunks < 1) {
            throw new IllegalArgumentException("checkpointEveryChunks must be at least one");
        }
        this.port = port;
        this.healthPolicy = healthPolicy;
        this.snapshotSupplier = snapshotSupplier;
        this.store = store;
        this.callbackExecutor = callbackExecutor;
        this.clock = clock;
        this.maxInFlight = maxInFlight;
        this.checkpointEveryChunks = checkpointEveryChunks;
        this.checkpoint = initialCheckpoint;
        this.logger = logger;
    }

    /** Current durable state, safe to read from any thread. Empty until a job has been created. */
    public Optional<PregenerationCheckpoint> status() {
        return Optional.ofNullable(checkpoint);
    }

    /** Chunk futures currently outstanding. Never exceeds the configured maximum. */
    public int inFlightCount() {
        return inFlight.get();
    }

    /** Creates a fresh job, replacing any completed or cancelled one. Starts paused. */
    public void begin(PregenerationSpec spec, String configFingerprint, String dataFingerprint) {
        callbackExecutor.execute(() -> {
            if (checkpoint != null && isActive(checkpoint.state())) {
                return;
            }
            checkpoint = new PregenerationCheckpoint(PregenerationCheckpoint.SCHEMA_VERSION, spec, 0, 0, 0, 0,
                    PregenerationState.PAUSED, "created", configFingerprint, dataFingerprint, clock.millis());
            terminalSinceCheckpoint = 0;
            saveCheckpoint();
        });
    }

    /** Manually resumes a paused or auto-paused job and attempts to fill dispatch slots. */
    public void resume() {
        callbackExecutor.execute(() -> {
            if (checkpoint == null || !checkpoint.state().mayTransitionTo(PregenerationState.RUNNING)) {
                return;
            }
            checkpoint = checkpoint.withState(PregenerationState.RUNNING, "resumed", clock.millis());
            saveCheckpoint();
            pump();
        });
    }

    /**
     * Re-checks {@link ServerHealthPolicy} for a job that is currently {@code AUTO_PAUSED} and resumes
     * it once the policy allows dispatch again; a no-op in every other state (including a manually
     * {@code PAUSED} job, which must stay paused until an operator explicitly resumes it).
     *
     * <p>Intended to be called periodically (e.g. from a repeating scheduler task) rather than only in
     * response to a completion callback or an explicit {@link #resume()} call: without a periodic
     * caller, a job that auto-paused for a transient reason (or that landed back in the health policy's
     * stability window after a manual {@link #resume()}) would never be re-evaluated and would stay
     * auto-paused forever. Each call only ever flips the job to {@code RUNNING} when the policy already
     * reports {@code mayDispatch()}, so it can never thrash the state by resuming and immediately
     * auto-pausing again in the same call.
     */
    public void reevaluateHealth() {
        callbackExecutor.execute(() -> {
            if (checkpoint == null || checkpoint.state() != PregenerationState.AUTO_PAUSED) {
                return;
            }
            ServerHealthPolicy.HealthDecision decision =
                    healthPolicy.evaluate(snapshotSupplier.get(), clock.instant());
            if (!decision.mayDispatch()) {
                return;
            }
            checkpoint = checkpoint.withState(PregenerationState.RUNNING, "auto-resumed", clock.millis());
            saveCheckpoint();
            pump();
        });
    }

    /** Manually pauses a running job. In-flight futures still complete; their callbacks just stop re-dispatching. */
    public void pause(String reason) {
        callbackExecutor.execute(() -> {
            if (checkpoint == null || !checkpoint.state().mayTransitionTo(PregenerationState.PAUSED)) {
                return;
            }
            checkpoint = checkpoint.withState(PregenerationState.PAUSED, reason, clock.millis());
            saveCheckpoint();
        });
    }

    /** Cancels the job and deletes its durable checkpoint. Never touches already-generated world chunks. */
    public void cancel() {
        callbackExecutor.execute(() -> {
            if (checkpoint == null || !checkpoint.state().mayTransitionTo(PregenerationState.CANCELLED)) {
                return;
            }
            checkpoint = checkpoint.withState(PregenerationState.CANCELLED, "cancelled", clock.millis());
            try {
                store.delete();
            } catch (IOException exception) {
                logger.warning("Cannot delete pregeneration checkpoint: " + exception.getMessage());
            }
        });
    }

    /**
     * Stops dispatching new work and writes one final checkpoint. Does not wait for outstanding chunk
     * futures -- their completions, if they still arrive, are handled normally by whatever executor
     * survives the shutdown.
     */
    public void shutdown() {
        stopped.set(true);
        callbackExecutor.execute(() -> {
            if (checkpoint != null && isActive(checkpoint.state())) {
                saveCheckpoint();
            }
        });
    }

    /** Fills dispatch slots up to {@code maxInFlight}, consulting health before every single dispatch. */
    private void pump() {
        if (stopped.get() || checkpoint == null || checkpoint.state() != PregenerationState.RUNNING) {
            return;
        }
        while (inFlight.get() < maxInFlight) {
            if (checkpoint.state() != PregenerationState.RUNNING) {
                // A reentrant pump() from a synchronously-completed chunk future (inline
                // callbackExecutor) may have already paused, auto-paused, or completed the job
                // while this frame was still on the stack. Don't act on stale state.
                return;
            }
            if (processedCount() >= checkpoint.spec().totalChunks()) {
                completeJob();
                return;
            }
            ServerHealthPolicy.HealthDecision decision =
                    healthPolicy.evaluate(snapshotSupplier.get(), clock.instant());
            if (!decision.mayDispatch()) {
                autoPause(decision.reason());
                return;
            }
            dispatchNext();
        }
    }

    /** Completed + skipped + failed chunks so far -- the only count that can equal {@code totalChunks},
     *  since the spiral's raw ordinal advances past chunks outside a non-square requested region too. */
    private long processedCount() {
        return checkpoint.completed() + checkpoint.skipped() + checkpoint.failed();
    }

    private void dispatchNext() {
        PregenerationSpec spec = checkpoint.spec();
        long ordinalCeiling = spec.spiralOrdinalCeiling();
        SpiralCursor.Chunk target = null;
        while (checkpoint.cursorOrdinal() < ordinalCeiling) {
            SpiralCursor.Chunk relative = SpiralCursor.at(checkpoint.cursorOrdinal());
            SpiralCursor.Chunk candidate = new SpiralCursor.Chunk(
                    relative.x() + spec.centerChunkX(), relative.z() + spec.centerChunkZ());
            advanceCursor();
            if (spec.contains(candidate)) {
                target = candidate;
                break;
            }
            // The square spiral steps outside a non-square/off-16-aligned requested region; skip that
            // ordinal without counting it as completed/skipped/failed and keep walking the spiral. The
            // region is finite and the spiral is unbounded and never repeats a chunk, but the ceiling
            // above still bounds this search: without it, a cursor left past the last in-bounds chunk
            // (e.g. by max-in-flight > 1 dispatching once more before the last completion lands, or by
            // a crash/resume that persisted the advanced ordinal without its completion) would search
            // forever for a candidate that no longer exists.
        }
        if (target == null) {
            // Ceiling reached without finding an in-bounds candidate. spiralOrdinalCeiling() is derived
            // to guarantee full coverage of the spec's bounds, so this shouldn't normally happen -- but
            // it's a safe backstop regardless: treat the region as exhausted instead of looping forever.
            completeJob();
            return;
        }
        final SpiralCursor.Chunk resolved = target;
        if (port.isChunkGenerated(resolved.x(), resolved.z())) {
            checkpoint = new PregenerationCheckpoint(checkpoint.schemaVersion(), checkpoint.spec(),
                    checkpoint.cursorOrdinal(), checkpoint.completed(), checkpoint.skipped() + 1,
                    checkpoint.failed(), checkpoint.state(), checkpoint.pauseReason(),
                    checkpoint.configFingerprint(), checkpoint.dataFingerprint(), clock.millis());
            recordTerminal();
            return;
        }
        inFlight.incrementAndGet();
        CompletableFuture<Boolean> future;
        try {
            future = port.loadOrGenerate(resolved.x(), resolved.z());
        } catch (RuntimeException exception) {
            inFlight.decrementAndGet();
            onChunkFailed(exception);
            return;
        }
        future.whenCompleteAsync((success, error) -> {
            inFlight.decrementAndGet();
            if (error != null) {
                onChunkFailed(error);
            } else if (Boolean.FALSE.equals(success)) {
                onChunkFailed(new IllegalStateException("chunk generation reported failure at "
                        + resolved.x() + ", " + resolved.z()));
            } else {
                onChunkSucceeded();
            }
        }, callbackExecutor);
    }

    private void onChunkSucceeded() {
        checkpoint = new PregenerationCheckpoint(checkpoint.schemaVersion(), checkpoint.spec(),
                checkpoint.cursorOrdinal(), checkpoint.completed() + 1, checkpoint.skipped(),
                checkpoint.failed(), checkpoint.state(), checkpoint.pauseReason(),
                checkpoint.configFingerprint(), checkpoint.dataFingerprint(), clock.millis());
        recordTerminal();
        pump();
    }

    private void onChunkFailed(Throwable error) {
        logger.warning("Pregeneration chunk failed: " + error.getMessage());
        checkpoint = new PregenerationCheckpoint(checkpoint.schemaVersion(), checkpoint.spec(),
                checkpoint.cursorOrdinal(), checkpoint.completed(), checkpoint.skipped(),
                checkpoint.failed() + 1, checkpoint.state(), checkpoint.pauseReason(),
                checkpoint.configFingerprint(), checkpoint.dataFingerprint(), clock.millis());
        saveCheckpoint();
        autoPause("chunk-generation-failed");
    }

    private void completeJob() {
        checkpoint = checkpoint.withState(PregenerationState.COMPLETED, "all chunks processed", clock.millis());
        saveCheckpoint();
    }

    private void autoPause(String reason) {
        if (checkpoint.state().mayTransitionTo(PregenerationState.AUTO_PAUSED)) {
            checkpoint = checkpoint.withState(PregenerationState.AUTO_PAUSED, reason, clock.millis());
            saveCheckpoint();
        }
    }

    private void advanceCursor() {
        checkpoint = new PregenerationCheckpoint(checkpoint.schemaVersion(), checkpoint.spec(),
                checkpoint.cursorOrdinal() + 1, checkpoint.completed(), checkpoint.skipped(),
                checkpoint.failed(), checkpoint.state(), checkpoint.pauseReason(),
                checkpoint.configFingerprint(), checkpoint.dataFingerprint(), clock.millis());
    }

    private void recordTerminal() {
        terminalSinceCheckpoint++;
        if (terminalSinceCheckpoint >= checkpointEveryChunks) {
            saveCheckpoint();
        }
    }

    private void saveCheckpoint() {
        try {
            store.save(checkpoint);
        } catch (IOException exception) {
            logger.warning("Cannot save pregeneration checkpoint: " + exception.getMessage());
        }
        terminalSinceCheckpoint = 0;
    }

    private static boolean isActive(PregenerationState state) {
        return state == PregenerationState.RUNNING || state == PregenerationState.PAUSED
                || state == PregenerationState.AUTO_PAUSED;
    }
}
