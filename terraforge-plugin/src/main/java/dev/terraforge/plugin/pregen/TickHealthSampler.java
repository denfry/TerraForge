package dev.terraforge.plugin.pregen;

import org.bukkit.Server;

/**
 * TPS and MSPT that work on Paper and on Folia.
 *
 * <p>Paper answers {@link Server#getTPS()} and {@link Server#getAverageTickTime()} from any thread.
 * Folia answers them only on a region thread, for that region, and throws
 * {@link UnsupportedOperationException} elsewhere, including on the global region thread that runs
 * pregeneration's bookkeeping. So every chunk completion, which Folia delivers on the thread of the
 * region that owns the chunk, records that region's numbers here, and a reader that cannot ask the
 * server directly gets the last recorded ones: the health of the region pregeneration is loading,
 * which is the region it can slow down.
 *
 * <p>Before the first completion there is nothing generating yet, so the defaults read as healthy.
 */
public final class TickHealthSampler {

    private volatile double tps = 20.0;
    private volatile double mspt = 0.0;

    /** Records the current thread's numbers, when the server can give them here. */
    public void sample(Server server) {
        try {
            double sampledTps = server.getTPS()[0];
            double sampledMspt = server.getAverageTickTime();
            tps = sampledTps;
            mspt = sampledMspt;
        } catch (UnsupportedOperationException notOnARegion) {
            // Folia off a region thread: keep the last region sample.
        }
    }

    /** One-minute TPS of the current thread's region, or the last sample when that cannot be read here. */
    public double tps(Server server) {
        try {
            return server.getTPS()[0];
        } catch (UnsupportedOperationException notOnARegion) {
            return tps;
        }
    }

    /** Average MSPT of the current thread's region, or the last sample when that cannot be read here. */
    public double mspt(Server server) {
        try {
            return server.getAverageTickTime();
        } catch (UnsupportedOperationException notOnARegion) {
            return mspt;
        }
    }
}
