package dev.terraforge.plugin.world;

import dev.terraforge.core.config.VerticalProfile;
import java.nio.file.Path;
import java.util.List;

/**
 * Read-only result of checking every prerequisite; executable only when all checks pass. Beyond the
 * checks themselves, it carries everything {@link ManagedWorldService#stage} needs to assemble the
 * staged edits (rendered datapack, server-properties/bukkit.yml/paper-world.yml edits and the resulting
 * manifest) without re-deriving them from the live server.
 */
public record WorldCreationPlan(List<WorldCreationCheck> checks, Path serverRoot, Path worldContainer,
                                 String worldName, VerticalProfile verticalProfile, String configFingerprint,
                                 String dataFingerprint, PaperWorldSettingsEditor.ChunkSettings chunkSettings) {
    public WorldCreationPlan { checks = List.copyOf(checks); }
    public boolean executable() { return checks.stream().allMatch(WorldCreationCheck::passed); }
}
