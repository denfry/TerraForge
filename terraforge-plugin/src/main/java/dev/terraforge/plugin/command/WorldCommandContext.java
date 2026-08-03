package dev.terraforge.plugin.command;

import dev.terraforge.core.config.VerticalProfile;
import dev.terraforge.plugin.world.LiveWorldSnapshot;
import dev.terraforge.plugin.world.ManagedWorldEnvironment;
import dev.terraforge.plugin.world.ManagedWorldManifest;
import dev.terraforge.plugin.world.ManagedWorldService;
import dev.terraforge.plugin.world.PaperWorldSettingsEditor;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Everything {@link WorldCommandHandler} needs from the live plugin to drive {@link ManagedWorldService}.
 *
 * <p>Kept as a narrow interface, separate from {@code TerraForgePlugin}, so the handler is testable
 * with a fake implementation and never needs a running server.
 */
public interface WorldCommandContext {
    ManagedWorldService service();
    ManagedWorldEnvironment environment();
    String configuredWorldName();
    long minimumFreeDiskGb();
    VerticalProfile verticalProfile();
    Path demDirectory();
    PaperWorldSettingsEditor.ChunkSettings chunkSettings();
    Path serverRoot();
    Path worldContainer();

    /** Builds the live snapshot {@link ManagedWorldService#verify} checks a manifest against. */
    Function<ManagedWorldManifest, LiveWorldSnapshot> liveSnapshotFactory();
}
