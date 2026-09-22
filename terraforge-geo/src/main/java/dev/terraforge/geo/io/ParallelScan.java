package dev.terraforge.geo.io;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Maps a list of files to their headers on a short-lived, bounded thread pool.
 *
 * <p>Cataloguing a planet is tens of thousands of open-read-close round trips, and on a server's
 * disk each one is latency, not bandwidth: a whole-Earth DEM directory took nine seconds of the
 * main thread read one file after another. The reads are independent, so a few threads overlap the
 * latency; everything that decides what the catalogue contains stays with the caller, which walks
 * the results in input order, so the catalogue is identical to a sequential scan.
 *
 * <p>The pool is created and shut down per call rather than borrowed from the common pool: this
 * runs during plugin enable, where another plugin's work in the common pool must not be starved,
 * and where a lingering thread would outlive a disabled plugin.
 */
public final class ParallelScan {

    /** Below this many inputs, starting threads costs more than it saves. */
    static final int SEQUENTIAL_BELOW = 256;

    private static final int MAX_THREADS = 8;
    private static final AtomicInteger POOL_SEQUENCE = new AtomicInteger();

    private ParallelScan() {
    }

    /**
     * Applies {@code reader} to every input and returns the results in input order.
     *
     * <p>{@code reader} must be thread-safe and should report its own per-file failures (returning
     * {@code null} or a marker value); an unchecked exception escaping it fails the whole scan.
     */
    public static <T, R> List<R> map(List<T> inputs, Function<? super T, ? extends R> reader) {
        int threads = threadsFor(inputs.size());
        if (threads <= 1) {
            List<R> results = new ArrayList<>(inputs.size());
            for (T input : inputs) {
                results.add(reader.apply(input));
            }
            return results;
        }
        Object[] results = new Object[inputs.size()];
        String name = "TerraForge-Scan-" + POOL_SEQUENCE.incrementAndGet() + "-";
        AtomicInteger threadSequence = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, name + threadSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            // Contiguous slices, one per thread: no per-file task overhead, and each thread's
            // reads stay in directory order.
            int slice = (inputs.size() + threads - 1) / threads;
            List<Future<?>> futures = new ArrayList<>(threads);
            for (int from = 0; from < inputs.size(); from += slice) {
                int start = from;
                int end = Math.min(inputs.size(), from + slice);
                futures.add(pool.submit(() -> {
                    for (int index = start; index < end; index++) {
                        results[index] = reader.apply(inputs.get(index));
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while cataloguing", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (exception.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(exception.getCause());
        } finally {
            pool.shutdownNow();
        }
        List<R> ordered = new ArrayList<>(results.length);
        for (Object result : results) {
            @SuppressWarnings("unchecked")
            R typed = (R) result;
            ordered.add(typed);
        }
        return ordered;
    }

    static int threadsFor(int inputs) {
        if (inputs < SEQUENTIAL_BELOW) {
            return 1;
        }
        // The wait is disk latency, not CPU, so a small container still gains from four threads.
        int cores = Math.max(4, Runtime.getRuntime().availableProcessors());
        return Math.max(1, Math.min(MAX_THREADS, Math.min(cores, inputs / SEQUENTIAL_BELOW)));
    }
}
