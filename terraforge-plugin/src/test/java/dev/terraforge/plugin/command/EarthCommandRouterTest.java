package dev.terraforge.plugin.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.terraforge.core.config.VerticalProfile;
import dev.terraforge.plugin.EarthCommand;
import dev.terraforge.plugin.TerraForgePlugin;
import dev.terraforge.plugin.world.LiveWorldSnapshot;
import dev.terraforge.plugin.world.ManagedWorldEnvironment;
import dev.terraforge.plugin.world.ManagedWorldManifest;
import dev.terraforge.plugin.world.ManagedWorldService;
import dev.terraforge.plugin.world.PaperWorldSettingsEditor;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers routing: the {@code world} family goes to {@link WorldCommandHandler}, everything else falls
 * back to the legacy {@link EarthCommand} untouched. The legacy path is exercised only through routes
 * that never read plugin configuration (unknown verbs, help, permission-gated tab completion), so
 * these tests do not need a running server or a fully wired {@code TerraForgePlugin}.
 */
class EarthCommandRouterTest {
    @TempDir Path serverRoot;

    @Test void routesWorldToTheWorldHandler() {
        EarthCommandRouter router = router();
        CommandResult result = router.execute(sender(true), List.of("world", "status"));
        assertThat(result.lines()).isNotEmpty();
        assertThat(result.lines().get(0).text()).startsWith("Managed Earth:");
    }

    @Test void unknownTopLevelCommandFallsBackToLegacyHelp() {
        EarthCommandRouter router = router();
        CommandSender sender = sender(true);

        CommandResult result = router.execute(sender, List.of("frobnicate"));

        assertThat(result).isEqualTo(CommandResult.NONE);
        verify(sender, times(4)).sendMessage(any(Component.class));
    }

    @Test void emptyArgsFallBackToLegacyHelp() {
        EarthCommandRouter router = router();
        CommandSender sender = sender(true);

        router.execute(sender, List.of());

        verify(sender, times(4)).sendMessage(any(Component.class));
    }

    @Test void suggestionsCombineLegacyFamiliesAndTheWorldFamily() {
        EarthCommandRouter router = router();
        List<String> suggestions = router.suggest(sender(true), List.of("w"));
        assertThat(suggestions).contains("whereami", "world");
    }

    @Test void worldSuggestionIsOmittedWithoutPermission() {
        EarthCommandRouter router = router();
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        when(sender.hasPermission("terraforge.command.world")).thenReturn(false);

        List<String> suggestions = router.suggest(sender, List.of("w"));

        assertThat(suggestions).doesNotContain("world");
    }

    @Test void secondLevelSuggestionsDelegateToTheWorldHandler() {
        EarthCommandRouter router = router();
        List<String> suggestions = router.suggest(sender(true), List.of("world", "cr"));
        assertThat(suggestions).containsExactly("create");
    }

    private EarthCommandRouter router() {
        TerraForgePlugin plugin = mock(TerraForgePlugin.class);
        EarthCommand legacy = new EarthCommand(plugin);
        WorldCommandHandler worldHandler = new WorldCommandHandler(context());
        return new EarthCommandRouter(legacy, worldHandler);
    }

    private WorldCommandContext context() {
        ManagedWorldService service = new ManagedWorldService(serverRoot.resolve("plugins/TerraForge"));
        ManagedWorldEnvironment environment = new ManagedWorldEnvironment() {
            @Override public boolean isOfficialSupportedPaper() { return true; }
            @Override public boolean isWorldLoaded(String name) { return false; }
            @Override public boolean hasPreparedDem() { return true; }
            @Override public Path serverRoot() { return serverRoot; }
            @Override public Path worldContainer() { return serverRoot.resolve("world"); }
            @Override public long usableDiskBytes(Path path) { return Long.MAX_VALUE; }
        };
        return new WorldCommandContext() {
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
    }

    private static CommandSender sender(boolean permitted) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(permitted);
        return sender;
    }
}
