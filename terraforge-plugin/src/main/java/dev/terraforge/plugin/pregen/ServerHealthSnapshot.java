package dev.terraforge.plugin.pregen;

/** Adapter-provided server health values; policy itself has no Bukkit dependency. */
public record ServerHealthSnapshot(int onlinePlayers, double tps, double mspt, long usableDiskGb,
                                   boolean demCoverageAvailable, boolean recentFailure, boolean shuttingDown) {}
