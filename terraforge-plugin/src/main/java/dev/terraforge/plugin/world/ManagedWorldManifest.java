package dev.terraforge.plugin.world;

import java.util.List;

/** Immutable, fail-closed record of the files TerraForge is permitted to own while staging earth. */
public record ManagedWorldManifest(int schemaVersion, ManagedWorldState state, String worldName, int minY,
                                   int maxY, String configFingerprint, String dataFingerprint,
                                   String datapackFingerprint, List<String> ownedStagingFiles, String reason) {
    public static final int SCHEMA_VERSION = 1;
    /**
     * Datapack fingerprint of a world kept at the vanilla height (-64..320), which needs no height
     * datapack at all: the SHA-256 of nothing. Such a manifest is never matched against a datapack.
     */
    public static final String NO_DATAPACK_FINGERPRINT = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    public ManagedWorldManifest { ownedStagingFiles = List.copyOf(ownedStagingFiles); }

    /** True for a world at the vanilla height that was adopted without a height datapack. */
    public boolean withoutDatapack() {
        return minY == -64 && maxY == 320 && NO_DATAPACK_FINGERPRINT.equals(datapackFingerprint);
    }
}
