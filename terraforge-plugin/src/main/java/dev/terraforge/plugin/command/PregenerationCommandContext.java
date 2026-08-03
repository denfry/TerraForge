package dev.terraforge.plugin.command;

import dev.terraforge.plugin.pregen.PregenerationController;
import dev.terraforge.plugin.pregen.PregenerationSpec;
import dev.terraforge.plugin.pregen.ServerHealthSnapshot;
import java.time.Clock;
import java.util.Optional;

/**
 * Everything {@link PregenerationCommandHandler} needs from the live plugin, kept narrow and
 * server-free so the handler is testable with a fake implementation.
 */
public interface PregenerationCommandContext {
    /** Mirrors {@code TerraForgePlugin#managedWorldReady()}: false disables every pregeneration verb. */
    boolean managedWorldReady();

    /** Present once the managed Earth world is verified and loaded, exactly like {@code TerraForgePlugin}'s. */
    Optional<PregenerationController> controller();

    /** The bounds {@code /earth pregenerate full confirm} runs against -- the entire configured region. */
    PregenerationSpec fullRegionSpec();

    /** Recomputed live, the same algorithm used when a checkpoint is first created. */
    String currentConfigFingerprint();

    /** Recomputed live, the same algorithm used when a checkpoint is first created. */
    String currentDataFingerprint();

    /** Live server health, used both to report status and to gate resume. */
    ServerHealthSnapshot currentHealth();

    /** Configured minimum usable disk space, reported alongside live disk usage. */
    long reserveDiskGb();

    /** Configured online-player pause policy, reported alongside live player count. */
    boolean pauseWhenPlayersOnline();

    /** Clock used for checkpoint-age reporting; overridable in tests. */
    Clock clock();
}
