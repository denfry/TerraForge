package dev.terraforge.plugin.world;

import java.util.List;

/** Read-only result of checking every prerequisite; executable only when all checks pass. */
public record WorldCreationPlan(List<WorldCreationCheck> checks) {
    public WorldCreationPlan { checks = List.copyOf(checks); }
    public boolean executable() { return checks.stream().allMatch(WorldCreationCheck::passed); }
}
