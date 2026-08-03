package dev.terraforge.plugin.pregen;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PregenerationControllerTest {

    private static final String HASH = "a".repeat(64);
    private static final Logger LOGGER = Logger.getLogger("PregenerationControllerTest");

    @TempDir
    java.nio.file.Path directory;

    /** Radius 16 around the origin yields a 3x3 = 9 chunk job -- small enough to drive by hand. */
    private static PregenerationSpec smallSpec() {
        return PregenerationSpec.around(0, 0, 16);
    }

    private PregenerationCheckpointStore store() {
        return new PregenerationCheckpointStore(directory);
    }

    private static PregenerationController controller(ChunkGenerationPort port, PregenerationCheckpointStore store,
            java.util.concurrent.Executor executor, int maxInFlight, int checkpointEveryChunks) {
        return controller(port, store, executor, maxInFlight, checkpointEveryChunks, healthyPolicy());
    }

    private static PregenerationController controller(ChunkGenerationPort port, PregenerationCheckpointStore store,
            java.util.concurrent.Executor executor, int maxInFlight, int checkpointEveryChunks,
            ServerHealthPolicy healthPolicy) {
        PregenerationCheckpoint initial = new PregenerationCheckpoint(PregenerationCheckpoint.SCHEMA_VERSION,
                smallSpec(), 0, 0, 0, 0, PregenerationState.PAUSED, "test", HASH, HASH, 0);
        return new PregenerationController(port, healthPolicy, PregenerationControllerTest::healthySnapshot,
                store, executor, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), maxInFlight, checkpointEveryChunks,
                initial, LOGGER);
    }

    private static ServerHealthPolicy healthyPolicy() {
        // Zero stable-resume-seconds means the very first healthy sample already clears "stabilizing".
        return new ServerHealthPolicy(true, 18, 40, 10, 0);
    }

    private static ServerHealthSnapshot healthySnapshot() {
        return new ServerHealthSnapshot(0, 20, 20, 20, true, false, false);
    }

    /** In-memory {@link ChunkGenerationPort} whose futures the test completes by hand. */
    private static final class ControllableFakePort implements ChunkGenerationPort {
        private final Map<Long, CompletableFuture<Boolean>> pending = new LinkedHashMap<>();
        private final List<String> requestOrder = new java.util.ArrayList<>();
        private final Set<Long> alreadyGenerated;
        final AtomicInteger loadOrGenerateCalls = new AtomicInteger();

        ControllableFakePort(Set<Long> alreadyGenerated) {
            this.alreadyGenerated = alreadyGenerated;
        }

        private static long key(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
        }

        @Override
        public synchronized boolean isChunkGenerated(int chunkX, int chunkZ) {
            return alreadyGenerated.contains(key(chunkX, chunkZ));
        }

        @Override
        public synchronized CompletableFuture<Boolean> loadOrGenerate(int chunkX, int chunkZ) {
            loadOrGenerateCalls.incrementAndGet();
            requestOrder.add(chunkX + "," + chunkZ);
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            pending.put(key(chunkX, chunkZ), future);
            return future;
        }

        synchronized void complete(int chunkX, int chunkZ, boolean success) {
            pending.get(key(chunkX, chunkZ)).complete(success);
        }

        synchronized void fail(int chunkX, int chunkZ, Throwable error) {
            pending.get(key(chunkX, chunkZ)).completeExceptionally(error);
        }

        synchronized int outstandingCount() {
            return (int) pending.values().stream().filter(f -> !f.isDone()).count();
        }
    }

    @Test
    void inFlightNeverExceedsTheConfiguredMaximumEvenWithSlowFutures() {
        var port = new ControllableFakePort(Set.of());
        var controller = controller(port, store(), Runnable::run, 2, 128);

        controller.resume();

        assertThat(port.loadOrGenerateCalls.get()).isEqualTo(2);
        assertThat(controller.inFlightCount()).isEqualTo(2);
        assertThat(port.outstandingCount()).isEqualTo(2);
    }

    @Test
    void defaultMaxInFlightBehaviourMatchesOneWhenConfiguredAsOne() {
        var port = new ControllableFakePort(Set.of());
        var controller = controller(port, store(), Runnable::run, 1, 128);

        controller.resume();

        assertThat(port.loadOrGenerateCalls.get()).isEqualTo(1);
        assertThat(controller.inFlightCount()).isEqualTo(1);
    }

    @Test
    void noNextChunkStartsUntilTheProrCompletesAtMaxOne() {
        var port = new ControllableFakePort(Set.of());
        var controller = controller(port, store(), Runnable::run, 1, 128);

        controller.resume();
        assertThat(port.loadOrGenerateCalls.get()).isEqualTo(1);

        // Chunk (0,0) is the spiral's first ordinal.
        port.complete(0, 0, true);

        assertThat(port.loadOrGenerateCalls.get()).isEqualTo(2);
        assertThat(controller.inFlightCount()).isEqualTo(1);
    }

    @Test
    void alreadyGeneratedChunksIncrementSkippedWithoutLoading() {
        var port = new ControllableFakePort(Set.of(ControllableFakePort.key(0, 0)));
        var controller = controller(port, store(), Runnable::run, 1, 128);

        controller.resume();

        assertThat(port.loadOrGenerateCalls.get()).isEqualTo(1); // only the second chunk was requested
        assertThat(controller.status().orElseThrow().skipped()).isEqualTo(1);
    }

    @Test
    void exceptionsIncrementFailureCountCheckpointAndPause() throws Exception {
        var port = new ControllableFakePort(Set.of());
        var store = store();
        var controller = controller(port, store, Runnable::run, 1, 128);

        controller.resume();
        port.fail(0, 0, new RuntimeException("boom"));

        var status = controller.status().orElseThrow();
        assertThat(status.failed()).isEqualTo(1);
        assertThat(status.state()).isEqualTo(PregenerationState.AUTO_PAUSED);
        assertThat(status.pauseReason()).isEqualTo("chunk-generation-failed");
        // The failure must be checkpointed immediately, independent of checkpointEveryChunks.
        assertThat(store.load().orElseThrow().failed()).isEqualTo(1);
    }

    @Test
    void missingDemStopsBeforeAnyChunkIsDispatched() {
        var port = new ControllableFakePort(Set.of());
        ServerHealthPolicy noDemPolicy = new ServerHealthPolicy(true, 18, 40, 10, 0);
        PregenerationCheckpoint initial = new PregenerationCheckpoint(PregenerationCheckpoint.SCHEMA_VERSION,
                smallSpec(), 0, 0, 0, 0, PregenerationState.PAUSED, "test", HASH, HASH, 0);
        var controller = new PregenerationController(port, noDemPolicy,
                () -> new ServerHealthSnapshot(0, 20, 20, 20, false, false, false),
                store(), Runnable::run, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1, 128, initial, LOGGER);

        controller.resume();

        assertThat(port.loadOrGenerateCalls.get()).isZero();
        assertThat(controller.status().orElseThrow().state()).isEqualTo(PregenerationState.AUTO_PAUSED);
        assertThat(controller.status().orElseThrow().pauseReason()).isEqualTo("missing-dem-coverage");
    }

    @Test
    void checkpointWritesEveryNTerminalChunksAndOnShutdown() throws Exception {
        var port = new ControllableFakePort(Set.of());
        var store = store();
        var controller = controller(port, store, Runnable::run, 1, 3);

        // The spiral order for this spec is (0,0), (1,0), (1,1), (0,1), (-1,1), ...; each completion
        // synchronously triggers the next dispatch since maxInFlight is one.
        controller.resume(); // the resume transition itself checkpoints once, with completed() still 0
        port.complete(0, 0, true);
        port.complete(1, 0, true);
        // Two terminal chunks so far; threshold is three, so no completion-driven checkpoint yet.
        assertThat(store.load().orElseThrow().completed()).isZero();

        port.complete(1, 1, true);
        // Third terminal chunk reaches the threshold.
        assertThat(store.load().orElseThrow().completed()).isEqualTo(3);

        port.complete(0, 1, true);
        // A fourth completion hasn't reached the next threshold yet.
        assertThat(store.load().orElseThrow().completed()).isEqualTo(3);

        controller.shutdown();
        assertThat(store.load().orElseThrow().completed()).isEqualTo(4);
    }

    @Test
    void cancelRemovesJobStateButNeverDeletesWorldChunks() throws Exception {
        var port = new ControllableFakePort(Set.of());
        var store = store();
        var controller = controller(port, store, Runnable::run, 1, 128);
        controller.resume();

        controller.cancel();

        assertThat(controller.status().orElseThrow().state()).isEqualTo(PregenerationState.CANCELLED);
        assertThat(store.load()).isEmpty();
        // ChunkGenerationPort has no delete/remove operation at all, so cancellation is structurally
        // incapable of touching generated world chunks -- only the durable job record is removed.
    }

    @Test
    void pauseResumeCancelAndStatusAreRaceSafeUnderConcurrentCompletions() throws Exception {
        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
        try {
            var port = new ControllableFakePort(Set.of());
            var controller = controller(port, store(), callbackExecutor, 1, 128);
            controller.resume();

            ExecutorService callers = Executors.newFixedThreadPool(4);
            CountDownLatch ready = new CountDownLatch(1);
            List<CompletableFuture<Void>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) {
                tasks.add(CompletableFuture.runAsync(() -> {
                    await(ready);
                    controller.status();
                    controller.pause("racy-pause");
                    controller.resume();
                }, callers));
            }
            ready.countDown();
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            callers.shutdown();
            assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            // Quiesce the callback executor so every submitted task has actually run.
            callbackExecutor.submit(() -> { }).get(5, TimeUnit.SECONDS);

            assertThat(controller.inFlightCount()).isBetween(0, 1);
            assertThat(controller.status()).isPresent();
        } finally {
            callbackExecutor.shutdown();
        }
    }

    @Test
    void inlineExecutorWithAlreadyCompletedFutureDoesNotDoubleCompleteTheJob() {
        // Regression test: with a direct (inline) callbackExecutor, whenCompleteAsync on an
        // already-completed future runs onChunkSucceeded synchronously, re-entering pump() while
        // the outer pump() frame is still on the stack. For a radius-0 spec (one chunk), the
        // nested pump() drains the job and calls completeJob(); the fix must stop the outer loop
        // from also seeing "past the end" and calling completeJob() a second time.
        var port = new ChunkGenerationPort() {
            @Override
            public boolean isChunkGenerated(int chunkX, int chunkZ) {
                return false;
            }

            @Override
            public CompletableFuture<Boolean> loadOrGenerate(int chunkX, int chunkZ) {
                return CompletableFuture.completedFuture(true);
            }
        };
        PregenerationCheckpoint initial = new PregenerationCheckpoint(PregenerationCheckpoint.SCHEMA_VERSION,
                PregenerationSpec.around(0, 0, 0), 0, 0, 0, 0, PregenerationState.PAUSED, "test", HASH, HASH, 0);
        var controller = new PregenerationController(port, healthyPolicy(),
                PregenerationControllerTest::healthySnapshot, store(), Runnable::run,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1, 128, initial, LOGGER);

        controller.resume();

        var status = controller.status().orElseThrow();
        assertThat(status.state()).isEqualTo(PregenerationState.COMPLETED);
        assertThat(status.completed()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
