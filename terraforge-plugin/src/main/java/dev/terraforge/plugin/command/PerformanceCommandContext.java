package dev.terraforge.plugin.command;

import java.util.Optional;

/** Everything {@link PerformanceCommandHandler} needs from the live server, read-only. */
public interface PerformanceCommandContext {
    double tps();
    double mspt();
    int onlinePlayers();
    long usableDiskGb();

    /** Chunk futures currently outstanding, present only while a pregeneration controller exists. */
    Optional<Integer> pregenerationInFlight();

    /** Pregeneration job state, e.g. {@code "RUNNING"}, present only once a job has been created. */
    Optional<String> pregenerationState();
}
