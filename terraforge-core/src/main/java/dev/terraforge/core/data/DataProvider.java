package dev.terraforge.core.data;

import dev.terraforge.core.coord.GeoBounds;

/**
 * Base contract for every geodata source.
 *
 * <p>Providers are queried from generation threads, so implementations must be thread-safe and must
 * never perform blocking network I/O -- all data is prepared offline by the CLI and read from the
 * local cache (see docs/data-sources.md).
 */
public interface DataProvider extends AutoCloseable {

    /** Stable name used in logs and {@code /earth debug} output. */
    String name();

    /** Geographic area this provider has data for. Queries outside it must return "no data". */
    GeoBounds coverage();

    default boolean covers(double latitude, double longitude) {
        return coverage().contains(latitude, longitude);
    }

    /** Releases file handles / mapped buffers. Called on plugin disable. */
    @Override
    default void close() {
        // no-op by default
    }
}
