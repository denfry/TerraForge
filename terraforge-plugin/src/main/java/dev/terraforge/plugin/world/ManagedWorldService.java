package dev.terraforge.plugin.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Read-only planning policy for a restart-bound, primary earth creation. */
public final class ManagedWorldService {
    private static final long GIB = 1024L * 1024L * 1024L;
    public WorldCreationPlan plan(ManagedWorldEnvironment environment, String configuredName, long minimumFreeDiskGb) {
        List<WorldCreationCheck> checks = new ArrayList<>();
        checks.add(new WorldCreationCheck("paper", environment.isOfficialSupportedPaper(), "official supported Paper is required"));
        checks.add(new WorldCreationCheck("world-name", "earth".equals(configuredName), "managed primary world must be named earth"));
        checks.add(new WorldCreationCheck("loaded-world", !environment.isWorldLoaded("earth"), "earth must not already be loaded"));
        Path earth = environment.worldContainer().resolve("earth");
        checks.add(new WorldCreationCheck("existing-world", !Files.exists(earth), "earth directory must not already exist"));
        checks.add(new WorldCreationCheck("prepared-dem", environment.hasPreparedDem(), "prepared DEM tiles are required"));
        try { checks.add(new WorldCreationCheck("disk-space", environment.usableDiskBytes(environment.worldContainer()) >= minimumFreeDiskGb * GIB, "insufficient usable disk space")); }
        catch (IOException exception) { checks.add(new WorldCreationCheck("disk-space", false, "cannot inspect usable disk space")); }
        return new WorldCreationPlan(checks);
    }
}
