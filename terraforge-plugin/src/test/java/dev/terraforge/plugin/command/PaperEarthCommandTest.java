package dev.terraforge.plugin.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.terraforge.core.config.VerticalProfile;
import dev.terraforge.plugin.EarthCommand;
import dev.terraforge.plugin.TerraForgePlugin;
import dev.terraforge.plugin.pregen.PregenerationController;
import dev.terraforge.plugin.pregen.PregenerationSpec;
import dev.terraforge.plugin.pregen.ServerHealthSnapshot;
import dev.terraforge.plugin.world.LiveWorldSnapshot;
import dev.terraforge.plugin.world.ManagedWorldEnvironment;
import dev.terraforge.plugin.world.ManagedWorldManifest;
import dev.terraforge.plugin.world.ManagedWorldService;
import dev.terraforge.plugin.world.PaperWorldSettingsEditor;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Confirms the Paper {@code BasicCommand} adapter reads the sender from the source stack and delivers
 *  whatever {@link EarthCommandRouter} returns, without needing a running server. */
class PaperEarthCommandTest {
    @TempDir Path serverRoot;

    @Test void executeSendsTheRouterResultToTheSourceStackSender() {
        CommandSender sender = sender(true);
        CommandSourceStack stack = sourceStack(sender);
        PaperEarthCommand command = new PaperEarthCommand(router());

        command.execute(stack, new String[] {"world", "status"});

        verify(sender).sendMessage(any(Component.class));
    }

    @Test void executeSendsNothingWhenTheRouterDelegatedToTheLegacyPathItself() {
        CommandSender sender = sender(true);
        CommandSourceStack stack = sourceStack(sender);
        PaperEarthCommand command = new PaperEarthCommand(router());

        // "frobnicate" is not a family the router recognises, so it falls back to the legacy
        // EarthCommand, which already sent its own help messages -- execute() must not double-send.
        command.execute(stack, new String[] {"frobnicate"});

        verify(sender, org.mockito.Mockito.times(4)).sendMessage(any(Component.class));
    }

    @Test void suggestDelegatesToTheRouter() {
        CommandSender sender = sender(true);
        CommandSourceStack stack = sourceStack(sender);
        PaperEarthCommand command = new PaperEarthCommand(router());

        var suggestions = command.suggest(stack, new String[] {"world", "cr"});

        assertThat(suggestions).containsExactly("create");
    }

    private EarthCommandRouter router() {
        TerraForgePlugin plugin = mock(TerraForgePlugin.class);
        EarthCommand legacy = new EarthCommand(plugin);
        ManagedWorldService service = new ManagedWorldService(serverRoot.resolve("plugins/TerraForge"));
        ManagedWorldEnvironment environment = new ManagedWorldEnvironment() {
            @Override public boolean isOfficialSupportedPaper() { return true; }
            @Override public boolean isWorldLoaded(String name) { return false; }
            @Override public boolean hasPreparedDem() { return true; }
            @Override public Path serverRoot() { return serverRoot; }
            @Override public Path worldContainer() { return serverRoot.resolve("world"); }
            @Override public long usableDiskBytes(Path path) { return Long.MAX_VALUE; }
        };
        WorldCommandContext context = new WorldCommandContext() {
            @Override public ManagedWorldService service() { return service; }
            @Override public ManagedWorldEnvironment environment() { return environment; }
            @Override public String configuredWorldName() { return "earth"; }
            @Override public long minimumFreeDiskGb() { return 10; }
            @Override public VerticalProfile verticalProfile() { return VerticalProfile.regional(); }
            @Override public Path demDirectory() { return serverRoot.resolve("dem-fixture"); }
            @Override public PaperWorldSettingsEditor.ChunkSettings chunkSettings() {
                return new PaperWorldSettingsEditor.ChunkSettings(6000, 24, "10s");
            }
            @Override public Path serverRoot() { return serverRoot; }
            @Override public Path worldContainer() { return serverRoot.resolve("world"); }
            @Override public Function<ManagedWorldManifest, LiveWorldSnapshot> liveSnapshotFactory() {
                return manifest -> { throw new UnsupportedOperationException(); };
            }
        };
        return new EarthCommandRouter(legacy, new WorldCommandHandler(context),
                new PregenerationCommandHandler(new PregenerationCommandContext() {
                    @Override public boolean managedWorldReady() { return false; }
                    @Override public Optional<PregenerationController> controller() { return Optional.empty(); }
                    @Override public PregenerationSpec fullRegionSpec() { return PregenerationSpec.around(0, 0, 16); }
                    @Override public String currentConfigFingerprint() { return "a".repeat(64); }
                    @Override public String currentDataFingerprint() { return "a".repeat(64); }
                    @Override public ServerHealthSnapshot currentHealth() { return new ServerHealthSnapshot(0, 20, 20, 20, true, false, false); }
                    @Override public long reserveDiskGb() { return 10; }
                    @Override public boolean pauseWhenPlayersOnline() { return true; }
                    @Override public Clock clock() { return Clock.systemUTC(); }
                }),
                new DataCommandHandler(new DataCommandContext() {
                    @Override public int tileCount() { return 0; }
                    @Override public String coverageDescription() { return "none"; }
                    @Override public boolean hasBathymetry() { return false; }
                    @Override public int missingRequestedTileCount() { return 0; }
                    @Override public int cachedCorruptFileCount() { return 0; }
                    @Override public void refreshCorruptionAsync() { }
                }),
                new DoctorCommandHandler(List::of),
                new PerformanceCommandHandler(new PerformanceCommandContext() {
                    @Override public double tps() { return 20; }
                    @Override public double mspt() { return 20; }
                    @Override public int onlinePlayers() { return 0; }
                    @Override public long usableDiskGb() { return 100; }
                    @Override public Optional<Integer> pregenerationInFlight() { return Optional.empty(); }
                    @Override public Optional<String> pregenerationState() { return Optional.empty(); }
                }));
    }

    private static CommandSourceStack sourceStack(CommandSender sender) {
        CommandSourceStack stack = mock(CommandSourceStack.class);
        when(stack.getSender()).thenReturn(sender);
        return stack;
    }

    private static CommandSender sender(boolean permitted) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(permitted);
        return sender;
    }
}
