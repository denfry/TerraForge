package dev.terraforge.cli.progress;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A periodic "still working, here is how far" line for the long commands.
 *
 * <p>Preparing a planet is 65,000 downloads and as many transcodes. Both extremes of reporting fail
 * it: silence is indistinguishable from a hang, and a line per file is 65,000 lines that still do
 * not say how long is left. So progress is aggregate and time-throttled -- at most one line every
 * few seconds, whatever the work rate -- and it carries the three things an operator actually
 * decides on: how far in, how fast, and how much longer.
 *
 * <p>Throttled by time rather than by count on purpose. A count-based interval prints in a burst
 * when tiles are cached and goes quiet for minutes when they are not, which is precisely backwards.
 *
 * <p>Every method is safe to call from the worker threads that do the work.
 */
public final class ProgressReporter {

    private static final long INTERVAL_MILLIS = 3_000L;

    private final PrintStream out;
    private final String label;
    private final int total;
    private final boolean showBytes;
    private final long startedAt = System.nanoTime();

    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong lastPrintMillis = new AtomicLong(System.currentTimeMillis());

    private volatile boolean printed;

    /**
     * @param label     what is being counted, e.g. {@code "Downloaded"}
     * @param total     items expected; a non-positive total disables reporting entirely
     * @param showBytes include transferred bytes and a byte rate
     */
    public ProgressReporter(PrintStream out, String label, int total, boolean showBytes) {
        this.out = out;
        this.label = label;
        this.total = total;
        this.showBytes = showBytes;
    }

    /** Records one finished item. */
    public void step() {
        step(0L);
    }

    /** Records one finished item that moved {@code transferred} bytes. */
    public void step(long transferred) {
        if (transferred > 0) {
            bytes.addAndGet(transferred);
        }
        int done = completed.incrementAndGet();
        if (total <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastPrintMillis.get();
        // Only the thread that wins the compare-and-set prints, so a burst of completions is still
        // one line.
        if (now - last >= INTERVAL_MILLIS && lastPrintMillis.compareAndSet(last, now)) {
            print(done, false);
        }
    }

    /** Prints a final line if anything was reported, so the summary does not follow a stale one. */
    public void finish() {
        if (printed) {
            print(completed.get(), true);
        }
    }

    private void print(int done, boolean last) {
        printed = true;
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        double seconds = Math.max(0.001, elapsed.toMillis() / 1000.0);
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT,
                "  %s %,d/%,d  %.1f%%", label, done, total, 100.0 * done / total));
        if (showBytes) {
            long transferred = bytes.get();
            line.append(String.format(Locale.ROOT, "  %s  %s/s",
                    humanBytes(transferred), humanBytes((long) (transferred / seconds))));
        } else {
            line.append(String.format(Locale.ROOT, "  %.1f/s", done / seconds));
        }
        line.append("  elapsed ").append(humanDuration(elapsed));
        if (!last && done > 0 && done < total) {
            long remaining = (long) (seconds / done * (total - done));
            line.append("  eta ").append(humanDuration(Duration.ofSeconds(remaining)));
        }
        synchronized (out) {
            out.println(line);
            out.flush();
        }
    }

    /** Shared by the progress lines and the download summaries, so they agree on units. */
    public static String humanBytes(long value) {
        if (value < 1024) {
            return value + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double scaled = value;
        int unit = -1;
        while (scaled >= 1024 && unit < units.length - 1) {
            scaled /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", scaled, units[unit]);
    }

    /** Coarse on purpose: "2h14m" is the useful precision for a multi-hour wait, "2h14m07s" is not. */
    public static String humanDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return seconds / 60 + "m" + String.format(Locale.ROOT, "%02ds", seconds % 60);
        }
        return seconds / 3600 + "h" + String.format(Locale.ROOT, "%02dm", seconds % 3600 / 60);
    }
}
