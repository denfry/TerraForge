package dev.terraforge.cli.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.terraforge.cli.fetch.SourceCatalog.Download;
import dev.terraforge.core.coord.GeoBounds;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Re-running a fetch, against a local server that counts requests.
 *
 * <p>What matters here is what a second run does <em>not</em> do. A source tree is a cache, and
 * finishing an interrupted planet must not start it over -- neither by re-transferring the tiles it
 * already has nor by re-asking about the tens of thousands of ocean cells that will never exist.
 */
class FetchResumeTest {

    @TempDir Path sourceRoot;

    private HttpServer server;
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            if (path.contains("ocean")) {
                respond(exchange, 404, "");
            } else {
                respond(exchange, 200, "elevation data for " + path);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aSecondRunTransfersNothingItAlreadyHas() {
        List<Download> plan = List.of(land("one"), land("two"));

        assertThat(fetch(plan, false).downloaded()).isEqualTo(2);
        Fetcher.Summary second = fetch(plan, false);

        assertThat(second.downloaded()).isZero();
        assertThat(second.cached()).isEqualTo(2);
        assertThat(hits("/land-one.tif")).isEqualTo(1);
        assertThat(hits("/land-two.tif")).isEqualTo(1);
    }

    @Test
    void aSecondRunFetchesOnlyTheFilesThatAreMissing() {
        fetch(List.of(land("one")), false);

        Fetcher.Summary second = fetch(List.of(land("one"), land("two")), false);

        assertThat(second.downloaded()).isEqualTo(1);
        assertThat(second.cached()).isEqualTo(1);
        assertThat(hits("/land-one.tif")).isEqualTo(1);
        assertThat(hits("/land-two.tif")).isEqualTo(1);
    }

    /** The saving that matters at planet scale: ocean cells asked about once, not once per run. */
    @Test
    void anUnpublishedTileIsNotAskedAboutASecondTime() {
        List<Download> plan = List.of(land("one"), ocean("deep"));

        assertThat(fetch(plan, false).absent()).isEqualTo(1);
        assertThat(hits("/ocean-deep.tif")).isEqualTo(1);

        Fetcher.Summary second = fetch(plan, false);

        assertThat(second.absent()).isEqualTo(1);
        assertThat(hits("/ocean-deep.tif")).isEqualTo(1);
    }

    @Test
    void theRegistryOutlivesTheProcess() {
        fetch(List.of(ocean("deep")), false);

        assertThat(sourceRoot.resolve(AbsentRegistry.FILE_NAME)).exists();
        assertThat(AbsentRegistry.open(sourceRoot).size()).isEqualTo(1);
    }

    @Test
    void replaceAsksAboutUnpublishedTilesAgain() {
        List<Download> plan = List.of(ocean("deep"));
        fetch(plan, false);

        fetch(plan, true);

        assertThat(hits("/ocean-deep.tif")).isEqualTo(2);
    }

    @Test
    void replaceDownloadsEverythingAgain() {
        List<Download> plan = List.of(land("one"));
        fetch(plan, false);

        Fetcher.Summary second = fetch(plan, true);

        assertThat(second.downloaded()).isEqualTo(1);
        assertThat(second.cached()).isZero();
        assertThat(hits("/land-one.tif")).isEqualTo(2);
    }

    /** A half-written file is completed, never mistaken for a finished one. */
    @Test
    void aPartialFileIsCompletedRatherThanCounted() throws Exception {
        Path target = sourceRoot.resolve("dem").resolve("land-one.tif");
        Files.createDirectories(target.getParent());
        Files.writeString(target.resolveSibling("land-one.tif.part"), "partial");

        Fetcher.Summary summary = fetch(List.of(land("one")), false);

        assertThat(summary.downloaded()).isEqualTo(1);
        assertThat(target).content().isEqualTo("elevation data for /land-one.tif");
        assertThat(target.resolveSibling("land-one.tif.part")).doesNotExist();
    }

    /** A tile that appears later is picked up: an absence is not a permanent verdict on the file. */
    @Test
    void aFileThatArrivesLocallyIsUsedEvenIfItWasRecordedAbsent() throws Exception {
        fetch(List.of(ocean("deep")), false);
        Path target = sourceRoot.resolve("dem").resolve("ocean-deep.tif");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "hand-supplied elevation");

        Fetcher.Summary second = fetch(List.of(ocean("deep")), false);

        assertThat(second.cached()).isEqualTo(1);
        assertThat(second.absent()).isZero();
        assertThat(target).content().isEqualTo("hand-supplied elevation");
    }

    /**
     * A machine-level problem -- a full disk, a dead network -- fails everything. Grinding through
     * the rest of a planet takes hours and buries the one message that mattered.
     */
    @Test
    void stopsWhenEverythingIsFailingRatherThanGrindingOn() {
        server.stop(0);
        List<Download> plan = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            plan.add(land("tile-" + i));
        }

        Fetcher.Summary summary = fetch(plan, false);

        assertThat(summary.failed()).isGreaterThanOrEqualTo(50);
        // The point: it gave up well short of all 500 rather than trying every one.
        assertThat(summary.failed()).isLessThan(plan.size());
        server = null;
    }

    /** One bad file among good ones is stepped over, exactly as before. */
    @Test
    void aSingleFailureDoesNotStopTheRun() {
        List<Download> plan = List.of(land("one"), ocean("deep"), land("two"));

        Fetcher.Summary summary = fetch(plan, false);

        assertThat(summary.downloaded()).isEqualTo(2);
        assertThat(summary.absent()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
    }

    // --- helpers ------------------------------------------------------------

    private Fetcher.Summary fetch(List<Download> plan, boolean replace) {
        Fetcher.Options options = new Fetcher.Options(GeoBounds.world(),
                SourceCatalog.DemResolution.GLO_90, "cities15000", Set.of(), replace, 4);
        return new Fetcher(quiet(), quiet()).fetch(plan, options, sourceRoot);
    }

    private int hits(String path) {
        AtomicInteger count = requests.get(path);
        return count == null ? 0 : count.get();
    }

    private Download land(String name) {
        return download("land-" + name + ".tif");
    }

    private Download ocean(String name) {
        return download("ocean-" + name + ".tif");
    }

    private Download download(String fileName) {
        return new Download("dem", fileName,
                URI.create("http://localhost:" + server.getAddress().getPort() + "/" + fileName));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
        exchange.close();
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }
}
