package dev.terraforge.plugin.pregen;

import java.time.Instant;

/** Conservative dispatch gate with a stable-good hysteresis window. */
public final class ServerHealthPolicy {
    private final boolean pauseWhenPlayersOnline; private final double minimumTps; private final double maximumMspt;
    private final long minimumDiskGb; private final long stableSeconds; private Instant healthySince;
    public ServerHealthPolicy(boolean players, double tps, double mspt, long diskGb, long stableSeconds) { this.pauseWhenPlayersOnline=players; this.minimumTps=tps; this.maximumMspt=mspt; this.minimumDiskGb=diskGb; this.stableSeconds=stableSeconds; }
    public HealthDecision evaluate(ServerHealthSnapshot s, Instant now) {
        String reason = s.shuttingDown() ? "server-shutdown" : s.recentFailure() ? "recent-chunk-failure" : !s.demCoverageAvailable() ? "missing-dem-coverage" : s.usableDiskGb() < minimumDiskGb ? "low-disk-space" : pauseWhenPlayersOnline && s.onlinePlayers() > 0 ? "players-online" : s.tps() < minimumTps ? "low-tps" : s.mspt() > maximumMspt ? "high-mspt" : null;
        if (reason != null) { healthySince = null; return new HealthDecision(false, reason); }
        if (healthySince == null) healthySince = now;
        return now.isBefore(healthySince.plusSeconds(stableSeconds)) ? new HealthDecision(false, "stabilizing") : new HealthDecision(true, "healthy");
    }
    public record HealthDecision(boolean mayDispatch, String reason) {}
}
