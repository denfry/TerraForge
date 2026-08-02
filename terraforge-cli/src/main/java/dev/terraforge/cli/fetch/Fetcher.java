package dev.terraforge.cli.fetch;

import dev.terraforge.cli.fetch.SourceCatalog.DemResolution;
import dev.terraforge.cli.fetch.SourceCatalog.Download;
import dev.terraforge.cli.progress.ProgressReporter;
import dev.terraforge.core.coord.GeoBounds;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Builds the source tree {@code prepare-region} expects, by downloading what a bounding box needs.
 *
 * <p>The result is a plain directory of publisher-named files -- not a TerraForge format. An
 * operator who already has the data keeps using it; one who does not gets the same tree without a
 * browser. Nothing here converts anything: preparation stays in the {@code prepare-*} commands.
 *
 * <p>Failures are counted, not thrown. A region is normally fetched in one long run over a few
 * gigabytes, and losing an hour of downloads because one mirror hiccuped on the last tile would be
 * absurd -- the run reports what is missing and re-running fills only the gaps.
 */
public final class Fetcher {

    /** Every dataset that can be fetched, in the order the sources are usually needed. */
    public static final Set<String> DATASETS = new LinkedHashSet<>(
            List.of("dem", "bathymetry", "landcover", "boundaries", "cities", "water", "karst"));

    private final PrintStream out;
    private final PrintStream err;

    public Fetcher(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /** Concurrent transfers when the caller does not choose. Open-data mirrors are content-delivery
     * networks; the bottleneck is round-trip latency per file, not their bandwidth. */
    public static final int DEFAULT_PARALLELISM = 6;

    /** Beyond this a mirror starts to look like it is being hammered rather than used. */
    private static final int MAX_PARALLELISM = 16;

    /** Above this many files, per-file lines stop informing and start scrolling. */
    private static final int PER_FILE_LINE_LIMIT = 200;

    /**
     * Consecutive failures that mean the problem is the machine, not the mirror.
     *
     * <p>Individual failures are normal and are meant to be stepped over -- that is why a run
     * reports them and carries on. But a full disk or a dropped network fails <em>everything</em>,
     * and grinding through fifty thousand more of them takes hours and buries the one message that
     * mattered. Fifty in a row with nothing succeeding in between is no longer bad luck.
     */
    private static final int ABORT_AFTER_CONSECUTIVE_FAILURES = 50;

    /**
     * @param datasets       which of {@link #DATASETS} to fetch
     * @param citiesDataset  GeoNames export name, e.g. {@code cities15000}
     * @param replace        re-download files that are already present
     * @param parallelism    concurrent transfers; a planet-wide box is 60,000 files, and one at a
     *                       time would take weeks of latency alone
     */
    public record Options(GeoBounds bounds, DemResolution demResolution, String citiesDataset,
                          Set<String> datasets, boolean replace, int parallelism) {

        public Options {
            if (parallelism < 1 || parallelism > MAX_PARALLELISM) {
                throw new IllegalArgumentException(
                        "--parallel must be between 1 and " + MAX_PARALLELISM + ": " + parallelism);
            }
        }

        public Options(GeoBounds bounds, DemResolution demResolution, String citiesDataset,
                       Set<String> datasets, boolean replace) {
            this(bounds, demResolution, citiesDataset, datasets, replace, DEFAULT_PARALLELISM);
        }
    }

    /** @param absent files the publisher has none of -- ocean-only tiles, almost always */
    public record Summary(int downloaded, int cached, int absent, int failed, long bytes) {

        public boolean complete() {
            return failed == 0;
        }
    }

    /** The files {@code options} resolves to, in fetch order. */
    public static List<Download> plan(Options options) {
        List<Download> downloads = new ArrayList<>();
        if (options.datasets().contains("dem")) {
            downloads.addAll(SourceCatalog.dem(options.bounds(), options.demResolution()));
        }
        if (options.datasets().contains("bathymetry")) {
            downloads.addAll(SourceCatalog.bathymetry(options.bounds()));
        }
        if (options.datasets().contains("landcover")) {
            downloads.addAll(SourceCatalog.landcover(options.bounds()));
        }
        if (options.datasets().contains("boundaries")) {
            downloads.addAll(SourceCatalog.boundaries());
        }
        if (options.datasets().contains("cities")) {
            downloads.add(SourceCatalog.cities(options.citiesDataset()));
        }
        if (options.datasets().contains("water")) {
            downloads.addAll(SourceCatalog.water());
        }
        if (options.datasets().contains("karst")) {
            downloads.addAll(SourceCatalog.karst());
        }
        return List.copyOf(downloads);
    }

    /**
     * Reports what the plan would transfer without transferring it.
     *
     * <p>Worth its own mode: a continental box at 30 m runs to several gigabytes, and that is much
     * better learned in ten seconds of HEAD requests than an hour into a download.
     */
    public int report(Options options, Path sourceRoot) {
        List<Download> downloads = plan(options);
        Downloads client = new Downloads(silent());
        AtomicLong total = new AtomicLong();
        AtomicInteger unavailable = new AtomicInteger();
        AtomicInteger unreachable = new AtomicInteger();
        AtomicInteger cached = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> untrusted =
                new java.util.concurrent.atomic.AtomicReference<>();
        AbsentRegistry registry = options.replace()
                ? AbsentRegistry.disabled() : AbsentRegistry.open(sourceRoot);
        out.println("Would fetch into " + sourceRoot + ":");
        out.println("  asking " + downloads.size() + " file(s) for their size, "
                + options.parallelism() + " at a time...");
        // Sizing a planet is 70,000 HEAD requests. Without progress this looks like a hang for the
        // several minutes it takes, which is the opposite of what a "quick check first" is for.
        ProgressReporter progress = new ProgressReporter(out, "Checked", downloads.size(), true);
        run(options.parallelism(), downloads, download -> {
            Path target = sourceRoot.resolve(download.dataset()).resolve(download.fileName());
            if (Files.isRegularFile(target)) {
                // Already downloaded: it costs nothing more, so it is not part of "to download".
                cached.incrementAndGet();
                progress.step();
                return;
            }
            if (registry.contains(download)) {
                unavailable.incrementAndGet();
                progress.step();
                return;
            }
            OptionalLong size;
            try {
                size = client.size(download.uri());
            } catch (IOException exception) {
                // One flaky HEAD in seventy thousand should not print a line of its own; the count
                // at the end says how much of the estimate is missing. A rejected certificate is a
                // different animal -- it will fail every retry, on every file from that host, so it
                // is worth a line even here.
                if (!untrustedChainRemedy(exception).isEmpty()) {
                    untrusted.compareAndSet(null, download.label());
                }
                unreachable.incrementAndGet();
                progress.step();
                return;
            }
            if (size.isEmpty()) {
                unavailable.incrementAndGet();
                progress.step();
                return;
            }
            total.addAndGet(size.getAsLong());
            progress.step(size.getAsLong());
        });
        progress.finish();
        int remaining = downloads.size() - unavailable.get() - unreachable.get() - cached.get();
        out.printf(Locale.ROOT, "  %,d file(s), %s still to download%s%n",
                remaining, Downloads.humanBytes(total.get()),
                unavailable.get() == 0 ? "" : ", " + unavailable.get() + " not published (ocean-only tiles)");
        if (cached.get() > 0) {
            out.printf(Locale.ROOT, "  %,d file(s) are already in %s and are not counted above.%n",
                    cached.get(), sourceRoot);
        }
        if (unreachable.get() > 0) {
            out.printf(Locale.ROOT, "  %,d file(s) could not be reached; the real total is a little larger.%n",
                    unreachable.get());
        }
        if (untrusted.get() != null) {
            out.println("  At least one of them (" + untrusted.get() + ") was refused at the TLS "
                    + "handshake, not by the network:");
            out.println("  this JDK does not trust the server's certificate chain. On Windows, re-run "
                    + "with -Djavax.net.ssl.trustStoreType=Windows-ROOT;");
            out.println("  otherwise import the issuing root into the JDK truststore. See DATA_SOURCES.md.");
        }
        long usable = usableSpace(sourceRoot);
        if (usable >= 0) {
            out.printf(Locale.ROOT, "  Free space on %s: %s%n", sourceRoot, Downloads.humanBytes(usable));
            if (total.get() > usable) {
                out.println("  NOT ENOUGH SPACE for this download. Free up "
                        + Downloads.humanBytes(total.get() - usable)
                        + ", point --source-data at another drive, or fetch a smaller box.");
            }
        }
        long estimatedSeconds = remaining <= 0 ? 0
                : (long) (remaining * 1.5 / options.parallelism());
        if (estimatedSeconds > 600) {
            out.println("  At " + options.parallelism() + " transfers this is roughly "
                    + ProgressReporter.humanDuration(java.time.Duration.ofSeconds(estimatedSeconds))
                    + " of downloading, resumable at any point.");
        }
        return 0;
    }

    /** Downloads the plan into {@code sourceRoot}, one dataset directory per file. */
    public Summary fetch(Options options, Path sourceRoot) {
        return fetch(plan(options), options, sourceRoot);
    }

    /**
     * The same work against an explicit plan.
     *
     * <p>Package-private so the resume behaviour can be tested against a local server: the rules
     * about what a re-run must not transfer are the ones worth pinning down, and they are about the
     * source directory rather than about which mirrors a bounding box resolves to.
     */
    Summary fetch(List<Download> downloads, Options options, Path sourceRoot) {
        out.println("Fetching " + downloads.size() + " source file(s) into " + sourceRoot
                + " (" + options.parallelism() + " at a time)");
        out.println("DEM: Copernicus " + options.demResolution().description()
                + ", land cover: ESA WorldCover 10 m, vectors: Natural Earth "
                + SourceCatalog.NATURAL_EARTH_TAG + " and GeoNames " + options.citiesDataset() + ".");

        // What the publisher does not have is as much a part of the source cache as what it does.
        AbsentRegistry registry = options.replace()
                ? AbsentRegistry.disabled() : AbsentRegistry.open(sourceRoot);
        if (registry.size() > 0) {
            out.printf(Locale.ROOT, "%,d file(s) are already known to be unpublished and are not "
                    + "asked about again (--replace re-checks).%n", registry.size());
        }

        // Per-file lines are useful for a handful of files and useless for tens of thousands, where
        // an aggregate with a rate and an ETA is the only thing that answers "should I wait?".
        boolean perFile = downloads.size() <= PER_FILE_LINE_LIMIT;
        if (!perFile) {
            out.println("Progress is reported every few seconds; failures are always printed.");
        }
        ConsoleListener listener = new ConsoleListener(out, downloads.size(), perFile);
        Downloads client = new Downloads(listener);
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger consecutiveFailures = new AtomicInteger();
        AtomicBoolean aborted = new AtomicBoolean();
        run(options.parallelism(), downloads, download -> {
            if (aborted.get()) {
                return;
            }
            Path target = sourceRoot.resolve(download.dataset()).resolve(download.fileName());
            if (registry.contains(download) && !Files.isRegularFile(target)) {
                listener.finished(download.label(), Downloads.Status.ABSENT, 0L);
                return;
            }
            try {
                Downloads.Status status =
                        client.download(download.uri(), target, download.label(), options.replace());
                if (status == Downloads.Status.ABSENT) {
                    registry.record(download);
                } else if (download.fileName().endsWith(".zip")) {
                    unpack(download, target, options.replace());
                }
                consecutiveFailures.set(0);
            } catch (IOException | RuntimeException exception) {
                failed.incrementAndGet();
                synchronized (err) {
                    err.println("  failed: " + download.label() + ": " + exception.getMessage()
                            + untrustedChainRemedy(exception));
                }
                if (consecutiveFailures.incrementAndGet() >= ABORT_AFTER_CONSECUTIVE_FAILURES
                        && aborted.compareAndSet(false, true)) {
                    reportAbort(sourceRoot, exception);
                }
            }
        });

        listener.finish();
        Summary summary = new Summary(listener.downloaded.get(), listener.cached.get(),
                listener.absent.get(), failed.get(), listener.bytes.get());
        out.printf(Locale.ROOT,
                "Fetched %,d file(s) (%s), %,d already present, %,d not published, %,d failed.%n",
                summary.downloaded(), Downloads.humanBytes(summary.bytes()), summary.cached(),
                summary.absent(), summary.failed());
        if (summary.absent() > 0) {
            out.println("Files that are 'not published' are ocean-only tiles the publisher omits; "
                    + "that is normal and needs no action.");
        }
        printAttribution(options);
        return summary;
    }

    /**
     * Runs {@code action} over {@code items} on {@code parallelism} threads, waiting for all of them.
     *
     * <p>Nothing is thrown out of here: an individual failure is the action's business, and losing a
     * finished half of a planet-scale download because one mirror timed out would be absurd. One
     * thread means running inline, so a {@code --parallel 1} run behaves exactly as the serial
     * fetcher did, which is what makes it a usable fallback for a hostile network.
     */
    private static <T> void run(int parallelism, List<T> items, Consumer<T> action) {
        if (parallelism <= 1 || items.size() <= 1) {
            items.forEach(action);
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(parallelism, items.size()),
                runnable -> {
                    Thread thread = new Thread(runnable, "terraforge-fetch");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            List<Future<?>> futures = new ArrayList<>(items.size());
            for (T item : items) {
                futures.add(executor.submit(() -> action.accept(item)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException exception) {
                    // The action reports its own failures; this only catches one that escaped.
                    throw new IllegalStateException("Fetch task failed", exception.getCause());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Says what a wall of identical failures almost always means, and stops.
     *
     * <p>Free space is checked here rather than up front because that is when it is diagnostic: a
     * disk with room to spare points at the network, and a disk without points at itself.
     */
    private void reportAbort(Path sourceRoot, Exception cause) {
        err.println();
        err.println("Stopping: " + ABORT_AFTER_CONSECUTIVE_FAILURES
                + " downloads failed in a row, so this is not one bad file.");
        long usable = usableSpace(sourceRoot);
        if (usable >= 0) {
            err.println("  Free space on " + sourceRoot + ": " + Downloads.humanBytes(usable));
            if (usable < 1024L * 1024 * 1024) {
                err.println("  That is the likely cause. Free some space, or point --source-data at "
                        + "another drive; already-downloaded files are kept and not fetched again.");
            }
        }
        err.println("  Last error: " + cause.getMessage());
        err.println("  Nothing is lost -- re-run the same command to continue where this stopped.");
    }

    private static long usableSpace(Path path) {
        try {
            Path existing = path.toAbsolutePath();
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            return existing == null ? -1L : Files.getFileStore(existing).getUsableSpace();
        } catch (IOException | RuntimeException exception) {
            return -1L;
        }
    }

    /** The gazetteer arrives zipped; the importer wants the tab-separated file inside it. */
    private void unpack(Download download, Path archive, boolean replace) throws IOException {
        if (download.fileName().equals("WHYMAP_WOKAM_v1.zip")) {
            // Every shapefile in the archive, because which layer holds the karst polygons is
            // WOKAM's business and not a contract. The importer reads polygons and ignores the rest.
            Path directory = archive.toAbsolutePath().getParent();
            for (String name : ZipEntries.extractAll(archive, ".shp", directory, replace)) {
                out.println("  extracted " + download.dataset() + "/" + name);
            }
            return;
        }
        if (download.fileName().equals("HydroRIVERS_v10_shp.zip")) {
            for (String extension : List.of(".shp", ".dbf")) {
                Path target = archive.resolveSibling("HydroRIVERS_v10" + extension);
                if (ZipEntries.extract(archive, "HydroRIVERS_v10" + extension, target, replace)) {
                    out.println("  extracted " + download.dataset() + "/" + target.getFileName());
                }
            }
            return;
        }
        String stem = download.fileName().substring(0, download.fileName().length() - ".zip".length());
        Path target = archive.resolveSibling(stem + ".txt");
        if (ZipEntries.extract(archive, stem + ".txt", target, replace)) {
            out.println("  extracted " + download.dataset() + "/" + target.getFileName());
        }
    }

    /**
     * Every source here is free to use and every one of them requires credit. Printing it at the end
     * of a fetch is the only moment the operator is guaranteed to see which licences they just took
     * on.
     */
    private void printAttribution(Options options) {
        out.println();
        out.println("Attribution required by the sources you just downloaded:");
        if (options.datasets().contains("dem")) {
            out.println("  Copernicus DEM: (c) DLR e.V. 2010-2014 and (c) Airbus Defence and Space GmbH "
                    + "2014-2018, provided under COPERNICUS by the European Union and ESA.");
        }
        if (options.datasets().contains("bathymetry")) {
            out.println("  GEBCO Compilation Group (2024) GEBCO 2024 Grid.");
        }
        if (options.datasets().contains("landcover")) {
            out.println("  ESA WorldCover 2021 v200 (CC BY 4.0), (c) ESA WorldCover project 2021 / "
                    + "Contains modified Copernicus Sentinel data.");
        }
        if (options.datasets().contains("boundaries") || options.datasets().contains("water")) {
            out.println("  Natural Earth (public domain), naturalearthdata.com.");
        }
        if (options.datasets().contains("water")) {
            out.println("  HydroRIVERS v1.0 (CC BY 4.0), (c) World Wildlife Fund, Inc. 2006-2013, "
                    + "HydroSHEDS database, hydrosheds.org.");
        }
        if (options.datasets().contains("cities")) {
            out.println("  GeoNames (CC BY 4.0), geonames.org.");
        }
        if (options.datasets().contains("karst")) {
            out.println("  Datenquelle: WHYMAP WOKAM, (c) BGR Berlin, IAH Reading, KIT Karlsruhe, "
                    + "UNESCO Paris 2017.");
        }
        out.println("See DATA_SOURCES.md for the exact wording each licence expects.");
    }

    /**
     * The remedy for a rejected certificate chain, or an empty string for anything else.
     *
     * <p>A TLS failure is not an unreachable host, and reporting it as one sends the operator
     * looking for a network problem they do not have. It is a live case rather than a hypothetical:
     * BGR serves WOKAM from a chain rooted in HARICA's 2021 root, which Oracle JDK 21 does not carry
     * even though every browser on the same machine trusts it.
     */
    static String untrustedChainRemedy(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof javax.net.ssl.SSLHandshakeException) {
                return System.lineSeparator()
                        + "    The host is reachable; this JDK does not trust its certificate chain."
                        + System.lineSeparator()
                        + "    Re-run with -Djavax.net.ssl.trustStoreType=Windows-ROOT on Windows, or"
                        + System.lineSeparator()
                        + "    import the issuing root into the JDK truststore. See DATA_SOURCES.md.";
            }
        }
        return "";
    }

    /** Validates a {@code --skip}/{@code --only} style dataset list. */
    public static Set<String> datasets(List<String> skip) {
        Set<String> selected = new LinkedHashSet<>(DATASETS);
        for (String entry : skip == null ? List.<String>of() : skip) {
            String name = entry.trim().toLowerCase(Locale.ROOT);
            if (!DATASETS.contains(name)) {
                throw new IllegalArgumentException("Unknown dataset '" + entry + "'; expected one of "
                        + String.join(", ", DATASETS));
            }
            selected.remove(name);
        }
        return selected;
    }

    private static Downloads.Listener silent() {
        return new Downloads.Listener() {
            @Override
            public void starting(String name, OptionalLong bytes) {
            }

            @Override
            public void finished(String name, Downloads.Status status, long bytes) {
            }

            @Override
            public void retrying(String name, int attempt, String reason) {
            }
        };
    }

    /**
     * One complete line per file, printed when that file is done.
     *
     * <p>Transfers overlap, so a line cannot be built up in pieces as the old serial listener did --
     * two threads would interleave mid-line. Each outcome is therefore one atomic {@code println},
     * and the counter in it is a completion count rather than a position in the plan.
     */
    private static final class ConsoleListener implements Downloads.Listener {

        private final PrintStream out;
        private final int total;
        private final boolean perFile;
        private final ProgressReporter progress;

        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger downloaded = new AtomicInteger();
        private final AtomicInteger cached = new AtomicInteger();
        private final AtomicInteger absent = new AtomicInteger();
        private final AtomicLong bytes = new AtomicLong();

        private ConsoleListener(PrintStream out, int total, boolean perFile) {
            this.out = out;
            this.total = total;
            this.perFile = perFile;
            this.progress = new ProgressReporter(out, "Fetched", total, true);
        }

        @Override
        public void starting(String name, OptionalLong size) {
            // Reported on completion instead; a size printed now would land in another file's line.
        }

        @Override
        public void finished(String name, Downloads.Status status, long size) {
            String outcome = switch (status) {
                case DOWNLOADED -> {
                    downloaded.incrementAndGet();
                    bytes.addAndGet(size);
                    yield Downloads.humanBytes(size) + " done";
                }
                case CACHED -> {
                    cached.incrementAndGet();
                    yield "already present";
                }
                case ABSENT -> {
                    absent.incrementAndGet();
                    yield "not published";
                }
            };
            int done = completed.incrementAndGet();
            if (perFile) {
                synchronized (out) {
                    out.printf(Locale.ROOT, "[%d/%d] %s %s%n", done, total, name, outcome);
                    out.flush();
                }
            }
            // Bytes on the wire, so the rate is transfer speed rather than cache-hit speed.
            progress.step(status == Downloads.Status.DOWNLOADED ? size : 0L);
        }

        /** Always printed: a mirror going soft is the one thing worth interrupting a quiet run for. */
        @Override
        public void retrying(String name, int attempt, String reason) {
            synchronized (out) {
                out.println("  " + name + ": attempt " + attempt + " failed (" + reason + "), retrying");
                out.flush();
            }
        }

        void finish() {
            progress.finish();
        }
    }
}
