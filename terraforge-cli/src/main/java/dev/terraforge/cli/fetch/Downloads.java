package dev.terraforge.cli.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Downloads one source file, with the three behaviours the rest of the CLI depends on.
 *
 * <p><b>A missing file is not an error.</b> The open-data buckets publish a tile only where there is
 * something to publish: an all-ocean degree cell has no Copernicus tile at all. A 404 is therefore
 * reported as {@link Status#ABSENT} and the caller carries on, exactly as {@code prepare-region}
 * treats a missing dataset directory as "no such data yet".
 *
 * <p><b>A half-written file is never left behind.</b> Bytes land in a sibling {@code .part} file and
 * are moved onto the target only after the stream ends, so an interrupted run cannot leave a
 * truncated GeoTIFF that the importer would later reject. An interrupted {@code .part} is resumed
 * with a range request when the server allows it.
 *
 * <p><b>An already-downloaded file is not downloaded again.</b> A source directory is a cache: the
 * expensive part of preparing a region is the multi-gigabyte download, and re-running {@code setup}
 * after fixing a bounding box must not repeat it.
 */
public final class Downloads {

    /** Transient failures are retried; a slow mirror is far more common than a broken one. */
    private static final int ATTEMPTS = 3;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);

    private static final long RETRY_BACKOFF_MILLIS = 1_000L;

    private final HttpClient client;
    private final String userAgent;
    private final Listener listener;

    public Downloads(Listener listener) {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        String version = Downloads.class.getPackage().getImplementationVersion();
        this.userAgent = "TerraForge-CLI/" + (version == null ? "dev" : version)
                + " (+https://github.com/denfry/TerraForge)";
        this.listener = listener;
    }

    /** What happened to one file. */
    public enum Status {
        /** Fetched from the network. */
        DOWNLOADED,
        /** Already present locally; nothing was transferred. */
        CACHED,
        /** The source publishes no such file -- normally an all-ocean tile. */
        ABSENT
    }

    /** Progress and outcome reporting, so this class stays free of {@code System.out}. */
    public interface Listener {
        void starting(String name, OptionalLong bytes);

        void finished(String name, Status status, long bytes);

        void retrying(String name, int attempt, String reason);
    }

    /**
     * Downloads {@code source} to {@code target}, creating parent directories.
     *
     * @param replace re-download even when the target already exists
     */
    public Status download(URI source, Path target, String name, boolean replace) throws IOException {
        if (!replace && Files.isRegularFile(target) && Files.size(target) > 0) {
            listener.finished(name, Status.CACHED, Files.size(target));
            return Status.CACHED;
        }
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        if (replace) {
            Files.deleteIfExists(partial);
        }

        IOException lastFailure = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                Status status = transfer(source, target, partial, name);
                if (status == Status.ABSENT) {
                    Files.deleteIfExists(partial);
                }
                return status;
            } catch (IOException exception) {
                lastFailure = exception;
                if (attempt < ATTEMPTS) {
                    listener.retrying(name, attempt, exception.getMessage());
                    sleep(RETRY_BACKOFF_MILLIS * attempt);
                }
            }
        }
        throw new IOException("Cannot download " + source + ": " + lastFailure.getMessage(), lastFailure);
    }

    /** Content length reported by the server, used to size a run before starting it. */
    public OptionalLong size(URI source) throws IOException {
        HttpRequest request = request(source).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<Void> response = send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 404 || response.statusCode() == 403) {
            return OptionalLong.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + source);
        }
        return response.headers().firstValueAsLong("content-length");
    }

    private Status transfer(URI source, Path target, Path partial, String name) throws IOException {
        long resumeFrom = Files.isRegularFile(partial) ? Files.size(partial) : 0L;
        HttpRequest.Builder builder = request(source).GET();
        if (resumeFrom > 0) {
            builder.header("Range", "bytes=" + resumeFrom + "-");
        }
        HttpResponse<InputStream> response = send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int code = response.statusCode();
        try (InputStream body = response.body()) {
            if (code == 404) {
                listener.finished(name, Status.ABSENT, 0L);
                return Status.ABSENT;
            }
            // 416 means the .part is already as long as the resource: the previous run finished the
            // transfer and died before the rename.
            if (code == 416 && resumeFrom > 0) {
                return publish(partial, target, name);
            }
            if (code != 200 && code != 206) {
                throw new IOException("HTTP " + code);
            }
            // A server that ignores the range restarts the file; appending would corrupt it.
            boolean append = code == 206 && resumeFrom > 0;
            long expected = response.headers().firstValueAsLong("content-length")
                    .orElse(-1L) + (append ? resumeFrom : 0L);
            listener.starting(name, expected > 0 ? OptionalLong.of(expected) : OptionalLong.empty());
            if (append) {
                Files.write(partial, body.readAllBytes(), java.nio.file.StandardOpenOption.APPEND);
            } else {
                Files.copy(body, partial, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return publish(partial, target, name);
    }

    private Status publish(Path partial, Path target, String name) throws IOException {
        long bytes = Files.size(partial);
        if (bytes == 0) {
            Files.deleteIfExists(partial);
            throw new IOException("the server returned an empty file");
        }
        try {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
        listener.finished(name, Status.DOWNLOADED, bytes);
        return Status.DOWNLOADED;
    }

    private HttpRequest.Builder request(URI source) {
        return HttpRequest.newBuilder(source)
                .header("User-Agent", userAgent)
                .timeout(REQUEST_TIMEOUT);
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        try {
            return client.send(request, handler);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Download interrupted");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** Human-readable byte count, used in every progress line the fetch commands print. */
    public static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
