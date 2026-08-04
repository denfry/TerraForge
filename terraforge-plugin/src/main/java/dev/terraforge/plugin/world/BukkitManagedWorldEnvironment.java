package dev.terraforge.plugin.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Server;

/** Bridges {@link ManagedWorldEnvironment} to a live Paper {@link Server}. */
public final class BukkitManagedWorldEnvironment implements ManagedWorldEnvironment {
    private final Server server;
    private final boolean hasPreparedDem;

    public BukkitManagedWorldEnvironment(Server server, boolean hasPreparedDem) {
        this.server = server;
        this.hasPreparedDem = hasPreparedDem;
    }

    /** Forks report a different server name, so an operator sees why the check failed rather than guessing. */
    @Override public boolean isOfficialSupportedPaper() { return "Paper".equals(server.getName()); }

    @Override public boolean isWorldLoaded(String name) { return server.getWorld(name) != null; }

    @Override public boolean hasPreparedDem() { return hasPreparedDem; }

    /** {@code server.properties} and {@code bukkit.yml} always live in the server's working directory. */
    @Override public Path serverRoot() { return Path.of("").toAbsolutePath(); }

    @Override public Path worldContainer() { return server.getWorldContainer().toPath().toAbsolutePath().normalize(); }

    @Override public long usableDiskBytes(Path path) throws IOException { return Files.getFileStore(path).getUsableSpace(); }
}
