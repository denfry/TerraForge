package dev.terraforge.cli.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercised against a real local HTTP server: the behaviour under test is protocol behaviour. */
class DownloadsTest {

    @TempDir Path temporaryDirectory;

    private HttpServer server;
    private final List<String> outcomes = new ArrayList<>();
    private Downloads downloads;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        downloads = new Downloads(new RecordingListener());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void writesTheBodyAndLeavesNoPartialFileBehind() throws Exception {
        serve("/tile.tif", exchange -> respond(exchange, 200, "elevation"));
        Path target = temporaryDirectory.resolve("tile.tif");

        assertThat(downloads.download(uri("/tile.tif"), target, "tile", false))
                .isEqualTo(Downloads.Status.DOWNLOADED);
        assertThat(target).hasContent("elevation");
        assertThat(temporaryDirectory.resolve("tile.tif.part")).doesNotExist();
        assertThat(outcomes).containsExactly("tile=DOWNLOADED");
    }

    @Test
    void treatsAMissingTileAsAbsentRatherThanAFailure() throws Exception {
        serve("/ocean.tif", exchange -> respond(exchange, 404, "no such key"));
        Path target = temporaryDirectory.resolve("ocean.tif");

        assertThat(downloads.download(uri("/ocean.tif"), target, "ocean", false))
                .isEqualTo(Downloads.Status.ABSENT);
        assertThat(target).doesNotExist();
        assertThat(temporaryDirectory.resolve("ocean.tif.part")).doesNotExist();
    }

    @Test
    void doesNotTransferAFileThatIsAlreadyThere() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        serve("/tile.tif", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "fresh");
        });
        Path target = temporaryDirectory.resolve("tile.tif");
        Files.writeString(target, "cached");

        assertThat(downloads.download(uri("/tile.tif"), target, "tile", false))
                .isEqualTo(Downloads.Status.CACHED);
        assertThat(target).hasContent("cached");
        assertThat(requests).hasValue(0);
    }

    @Test
    void resumesAnInterruptedTransferInsteadOfStartingOver() throws Exception {
        // The server honours the range header, as the open-data buckets do.
        serve("/tile.tif", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            String body = "0123456789";
            if (range == null) {
                respond(exchange, 200, body);
                return;
            }
            int from = Integer.parseInt(range.replace("bytes=", "").replace("-", ""));
            exchange.getResponseHeaders().add("Content-Range", "bytes " + from + "-9/10");
            respond(exchange, 206, body.substring(from));
        });
        Path target = temporaryDirectory.resolve("tile.tif");
        Files.writeString(temporaryDirectory.resolve("tile.tif.part"), "0123");

        assertThat(downloads.download(uri("/tile.tif"), target, "tile", false))
                .isEqualTo(Downloads.Status.DOWNLOADED);
        assertThat(target).hasContent("0123456789");
    }

    @Test
    void retriesBeforeGivingUpOnAFailingMirror() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        serve("/tile.tif", exchange -> {
            if (attempts.incrementAndGet() < 3) {
                respond(exchange, 503, "try later");
            } else {
                respond(exchange, 200, "elevation");
            }
        });

        assertThat(downloads.download(uri("/tile.tif"), temporaryDirectory.resolve("tile.tif"), "tile", false))
                .isEqualTo(Downloads.Status.DOWNLOADED);
        assertThat(attempts).hasValue(3);
    }

    @Test
    void reportsAMirrorThatNeverRecovers() {
        serve("/tile.tif", exchange -> respond(exchange, 500, "broken"));

        assertThatThrownBy(() -> downloads.download(uri("/tile.tif"),
                temporaryDirectory.resolve("tile.tif"), "tile", false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void readsTheSizeWithoutFetchingTheBody() throws Exception {
        serve("/tile.tif", exchange -> respond(exchange, 200, "0123456789"));

        assertThat(downloads.size(uri("/tile.tif"))).isEqualTo(OptionalLong.of(10));
    }

    // --- helpers ------------------------------------------------------------

    private void serve(String path, ThrowingHandler handler) {
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if ("HEAD".equals(exchange.getRequestMethod())) {
            // The JDK server sends no Content-Length of its own for a bodyless response, so the
            // header the open-data buckets return has to be set explicitly.
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, status == 404 ? -1 : bytes.length);
        if (status != 404) {
            exchange.getResponseBody().write(bytes);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + server.getAddress().getPort() + path);
    }

    private interface ThrowingHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    private final class RecordingListener implements Downloads.Listener {
        @Override
        public void starting(String name, OptionalLong bytes) {
        }

        @Override
        public void finished(String name, Downloads.Status status, long bytes) {
            outcomes.add(name + "=" + status);
        }

        @Override
        public void retrying(String name, int attempt, String reason) {
        }
    }
}
