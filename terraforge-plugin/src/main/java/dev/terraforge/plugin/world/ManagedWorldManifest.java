package dev.terraforge.plugin.world;

import java.util.List;

/** Immutable, fail-closed record of the files TerraForge is permitted to own while staging earth. */
public record ManagedWorldManifest(int schemaVersion, ManagedWorldState state, String worldName, int minY,
                                   int maxY, String configFingerprint, String dataFingerprint,
                                   String datapackFingerprint, List<String> ownedStagingFiles, String reason) {
    public static final int SCHEMA_VERSION = 1;
    public ManagedWorldManifest { ownedStagingFiles = List.copyOf(ownedStagingFiles); }
}
