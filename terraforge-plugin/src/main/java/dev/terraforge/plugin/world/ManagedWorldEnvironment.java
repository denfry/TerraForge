package dev.terraforge.plugin.world;

import java.io.IOException;
import java.nio.file.Path;

/** Narrow server boundary, allowing the creation policy to be tested without Bukkit. */
public interface ManagedWorldEnvironment {
    boolean isOfficialSupportedPaper();
    boolean isWorldLoaded(String name);
    boolean hasPreparedDem();
    Path serverRoot();
    Path worldContainer();
    long usableDiskBytes(Path path) throws IOException;
}
