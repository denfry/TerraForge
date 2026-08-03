package dev.terraforge.plugin.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.terraforge.core.config.VerticalProfile;
import dev.terraforge.plugin.world.LiveWorldSnapshot;
import dev.terraforge.plugin.world.ManagedWorldEnvironment;
import dev.terraforge.plugin.world.ManagedWorldManifest;
import dev.terraforge.plugin.world.ManagedWorldManifestStore;
import dev.terraforge.plugin.world.ManagedWorldService;
import dev.terraforge.plugin.world.ManagedWorldState;
import dev.terraforge.plugin.world.PaperWorldSettingsEditor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parser/permission/output tests for {@code /earth world plan|create|status|verify|abort} -- none of
 * these need a running server: {@link ManagedWorldService} is exercised against a temp directory, the
 * live-server pieces are supplied through fakes, and {@link CommandSender} is mocked.
 */
class WorldCommandHandlerTest {
    private static final PaperWorldSettingsEditor.ChunkSettings CHUNK_SETTINGS =
            new PaperWorldSettingsEditor.ChunkSettings(6000, 24, "10s");

    @TempDir Path serverRoot;

    @Test void deniesExecutionWithoutPermission() {
        WorldCommandHandler handler = new WorldCommandHandler(context(executableEnvironment(), planOnlyService()));
        CommandResult result = handler.execute(sender(false), List.of("status"));
        assertThat(result.isError()).isTrue();
        assertThat(result.lines()).extracting(CommandResult.Line::text)
                .containsExactly("You do not have permission for this command.");
    }

    @Test void deniesTabCompletionWithoutPermission() {
        WorldCommandHandler handler = new WorldCommandHandler(context(executableEnvironment(), planOnlyService()));
        assertThat(handler.suggest(sender(false), List.of("st"))).isEmpty();
        assertThat(handler.isVisibleTo(sender(false))).isFalse();
    }

    @Test void reportsUsageForMissingArgs() {
        WorldCommandHandler handler = new WorldCommandHandler(context(executableEnvironment(), planOnlyService()));
        CommandResult result = handler.execute(sender(true), List.of());
        assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().get(0).text()).startsWith("Usage: /earth world <");
    }

    @Test void reportsUsageForExtraArgs() {
        WorldCommandHandler handler = new WorldCommandHandler(context(executableEnvironment(), planOnlyService()));
        CommandResult result = handler.execute(sender(true), List.of("status", "extra"));
        assertThat(result.lines().get(0).text()).startsWith("Usage: /earth world <");
    }

    @Test void reportsUsageForUnknownVerb() {
        WorldCommandHandler handler = new WorldCommandHandler(context(executableEnvironment(), planOnlyService()));
        CommandResult result = handler.execute(sender(true), List.of("bogus"));
        assertThat(result.lines().get(0).text()).startsWith("Usage: /earth world <");
    }

    @Test void suggestsVerbsFilteredByPrefix() {
        WorldCommandHandler handler = new WorldCommandHandler(context(executableEnvironment(), planOnlyService()));
        assertThat(handler.suggest(sender(true), List.of("cr"))).containsExactly("create");
        assertThat(handler.suggest(sender(true), List.of(""))).containsExactlyInAnyOrder(
                "plan", "create", "status", "verify", "abort");
    }

    @Test void planReportsEveryCheckAndThatTheResultIsExecutable() {
        WorldCommandHandler handler = new WorldCommandHandler(context(executableEnvironment(), planOnlyService()));
        CommandResult result = handler.execute(sender(true), List.of("plan"));
        assertThat(result.isError()).isFalse();
        assertThat(result.lines()).isNotEmpty();
        assertThat(result.lines().get(result.lines().size() - 1).text()).contains("Plan is executable");
    }

    @Test void planReportsFailingChecksAsNotExecutable() {
        ManagedWorldEnvironment brokenEnvironment = new FakeEnvironment(false, false, true, Long.MAX_VALUE);
        WorldCommandHandler handler = new WorldCommandHandler(context(brokenEnvironment, planOnlyService()));
        CommandResult result = handler.execute(sender(true), List.of("plan"));
        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(result.lines().size() - 1).text()).contains("Plan is not executable");
    }

    @Test void createStagesAnExecutablePlanAndPersistsAManifest() throws IOException {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        WorldCommandHandler handler = new WorldCommandHandler(context(executableEnvironment(), new ManagedWorldService(pluginRoot())));

        CommandResult result = handler.execute(sender(true), List.of("create"));

        assertThat(result.isError()).isFalse();
        assertThat(manifests.load()).isPresent();
        assertThat(manifests.load().orElseThrow().state()).isEqualTo(ManagedWorldState.PENDING_RESTART);
    }

    @Test void createRefusesANonExecutablePlanWithoutStaging() throws IOException {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        ManagedWorldEnvironment brokenEnvironment = new FakeEnvironment(false, false, true, Long.MAX_VALUE);
        WorldCommandHandler handler = new WorldCommandHandler(context(brokenEnvironment, new ManagedWorldService(pluginRoot())));

        CommandResult result = handler.execute(sender(true), List.of("create"));

        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(0).text()).contains("Cannot stage the managed Earth world");
        assertThat(manifests.load()).isEmpty();
    }

    @Test void statusReportsAbsentWhenNoManifestExists() {
        WorldCommandHandler handler = new WorldCommandHandler(context(executableEnvironment(), new ManagedWorldService(pluginRoot())));
        CommandResult result = handler.execute(sender(true), List.of("status"));
        assertThat(result.lines().get(0).text()).isEqualTo("Managed Earth: " + ManagedWorldState.ABSENT);
    }

    @Test void statusIsIdempotent() {
        WorldCommandHandler handler = new WorldCommandHandler(context(executableEnvironment(), new ManagedWorldService(pluginRoot())));
        CommandResult first = handler.execute(sender(true), List.of("status"));
        CommandResult second = handler.execute(sender(true), List.of("status"));
        assertThat(second).isEqualTo(first);
    }

    @Test void verifyReportsSuccessWhenTheLiveSnapshotMatches() throws IOException {
        ManagedWorldService service = new ManagedWorldService(pluginRoot());
        service.stage(planFor(executableEnvironment()));
        WorldCommandHandler handler = new WorldCommandHandler(context(executableEnvironment(), service, matchingSnapshotFactory()));

        CommandResult result = handler.execute(sender(true), List.of("verify"));

        assertThat(result.isError()).isFalse();
        assertThat(result.lines().get(0).text()).contains("verified");
    }

    @Test void verifyReportsEveryFailureWhenTheLiveSnapshotMismatches() throws IOException {
        ManagedWorldService service = new ManagedWorldService(pluginRoot());
        service.stage(planFor(executableEnvironment()));
        Function<ManagedWorldManifest, LiveWorldSnapshot> mismatched = manifest ->
                new LiveWorldSnapshot("spawn", false, false, 0, 0, false, "z".repeat(64), "z".repeat(64), "z".repeat(64));
        WorldCommandHandler handler = new WorldCommandHandler(context(executableEnvironment(), service, mismatched));

        CommandResult result = handler.execute(sender(true), List.of("verify"));

        assertThat(result.isError()).isTrue();
        assertThat(result.lines()).hasSizeGreaterThan(1);
    }

    @Test void verifyNotifiesTheContextOfASuccessfulResult() throws IOException {
        ManagedWorldService service = new ManagedWorldService(pluginRoot());
        service.stage(planFor(executableEnvironment()));
        List<Boolean> notified = new java.util.ArrayList<>();
        WorldCommandContext ctx = contextWithVerifyListener(executableEnvironment(), service,
                matchingSnapshotFactory(), notified::add);
        WorldCommandHandler handler = new WorldCommandHandler(ctx);

        handler.execute(sender(true), List.of("verify"));

        assertThat(notified).containsExactly(true);
    }

    @Test void verifyNotifiesTheContextOfAFailedResult() throws IOException {
        ManagedWorldService service = new ManagedWorldService(pluginRoot());
        service.stage(planFor(executableEnvironment()));
        Function<ManagedWorldManifest, LiveWorldSnapshot> mismatched = manifest ->
                new LiveWorldSnapshot("spawn", false, false, 0, 0, false, "z".repeat(64), "z".repeat(64), "z".repeat(64));
        List<Boolean> notified = new java.util.ArrayList<>();
        WorldCommandContext ctx = contextWithVerifyListener(executableEnvironment(), service, mismatched, notified::add);
        WorldCommandHandler handler = new WorldCommandHandler(ctx);

        handler.execute(sender(true), List.of("verify"));

        assertThat(notified).containsExactly(false);
    }

    @Test void abortRemovesAStagedWorldAndItsManifest() throws IOException {
        var manifests = new ManagedWorldManifestStore(pluginRoot());
        ManagedWorldService service = new ManagedWorldService(pluginRoot());
        service.stage(planFor(executableEnvironment()));
        WorldCommandHandler handler = new WorldCommandHandler(context(executableEnvironment(), service));

        CommandResult result = handler.execute(sender(true), List.of("abort"));

        assertThat(result.isError()).isFalse();
        assertThat(manifests.load()).isEmpty();
    }

    @Test void abortWithNothingStagedIsSanitizedForNonConsoleSenders() {
        WorldCommandHandler handler = new WorldCommandHandler(context(executableEnvironment(), new ManagedWorldService(pluginRoot())));
        CommandResult result = handler.execute(sender(true), List.of("abort"));
        assertThat(result.isError()).isTrue();
        String text = result.lines().get(0).text();
        assertThat(text).isEqualTo("Aborting the staged managed Earth world failed; see the console log for details.");
        assertThat(text).doesNotContain(serverRoot.toString());
    }

    @Test void consoleSendersSeeTheUnderlyingFailureDetail() {
        WorldCommandHandler handler = new WorldCommandHandler(context(executableEnvironment(), planOnlyService()));
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);

        CommandResult result = handler.execute(console, List.of("status"));

        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(0).text()).contains("ManagedWorldService(Path pluginRoot)");
    }

    // --- fixtures -------------------------------------------------------

    private static CommandSender sender(boolean permitted) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(permitted);
        return sender;
    }

    private ManagedWorldService planOnlyService() {
        return new ManagedWorldService();
    }

    private WorldCommandContext context(ManagedWorldEnvironment environment, ManagedWorldService service) {
        return context(environment, service, manifest -> {
            throw new UnsupportedOperationException("verify not exercised by this fixture");
        });
    }

    private WorldCommandContext context(ManagedWorldEnvironment environment, ManagedWorldService service,
                                         Function<ManagedWorldManifest, LiveWorldSnapshot> snapshotFactory) {
        Path dem = demDirectory();
        return new WorldCommandContext() {
            @Override public ManagedWorldService service() { return service; }
            @Override public ManagedWorldEnvironment environment() { return environment; }
            @Override public String configuredWorldName() { return "earth"; }
            @Override public long minimumFreeDiskGb() { return 10; }
            @Override public VerticalProfile verticalProfile() { return VerticalProfile.regional(); }
            @Override public Path demDirectory() { return dem; }
            @Override public PaperWorldSettingsEditor.ChunkSettings chunkSettings() { return CHUNK_SETTINGS; }
            @Override public Path serverRoot() { return serverRoot; }
            @Override public Path worldContainer() { return serverRoot.resolve("world"); }
            @Override public Function<ManagedWorldManifest, LiveWorldSnapshot> liveSnapshotFactory() { return snapshotFactory; }
        };
    }

    private WorldCommandContext contextWithVerifyListener(ManagedWorldEnvironment environment, ManagedWorldService service,
            Function<ManagedWorldManifest, LiveWorldSnapshot> snapshotFactory, java.util.function.Consumer<Boolean> onVerifyResult) {
        Path dem = demDirectory();
        return new WorldCommandContext() {
            @Override public ManagedWorldService service() { return service; }
            @Override public ManagedWorldEnvironment environment() { return environment; }
            @Override public String configuredWorldName() { return "earth"; }
            @Override public long minimumFreeDiskGb() { return 10; }
            @Override public VerticalProfile verticalProfile() { return VerticalProfile.regional(); }
            @Override public Path demDirectory() { return dem; }
            @Override public PaperWorldSettingsEditor.ChunkSettings chunkSettings() { return CHUNK_SETTINGS; }
            @Override public Path serverRoot() { return serverRoot; }
            @Override public Path worldContainer() { return serverRoot.resolve("world"); }
            @Override public Function<ManagedWorldManifest, LiveWorldSnapshot> liveSnapshotFactory() { return snapshotFactory; }
            @Override public void onVerifyResult(boolean ready) { onVerifyResult.accept(ready); }
        };
    }

    private Function<ManagedWorldManifest, LiveWorldSnapshot> matchingSnapshotFactory() {
        return manifest -> new LiveWorldSnapshot(manifest.worldName(), true, true, manifest.minY(), manifest.maxY(),
                true, manifest.configFingerprint(), manifest.dataFingerprint(), manifest.datapackFingerprint());
    }

    private dev.terraforge.plugin.world.WorldCreationPlan planFor(ManagedWorldEnvironment environment) {
        return new ManagedWorldService().plan(environment, "earth", 10, VerticalProfile.regional(), demDirectory(), CHUNK_SETTINGS);
    }

    private ManagedWorldEnvironment executableEnvironment() {
        return new FakeEnvironment(true, false, true, Long.MAX_VALUE);
    }

    private Path demDirectory() {
        Path dem = serverRoot.resolve("dem-fixture");
        try {
            Files.createDirectories(dem);
            Files.writeString(dem.resolve("tile-0.dem"), "elevation-fixture-data");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return dem;
    }

    private Path pluginRoot() { return serverRoot.resolve("plugins/TerraForge"); }

    private final class FakeEnvironment implements ManagedWorldEnvironment {
        private final boolean officialPaper;
        private final boolean worldLoaded;
        private final boolean preparedDem;
        private final long usableDiskBytes;

        FakeEnvironment(boolean officialPaper, boolean worldLoaded, boolean preparedDem, long usableDiskBytes) {
            this.officialPaper = officialPaper;
            this.worldLoaded = worldLoaded;
            this.preparedDem = preparedDem;
            this.usableDiskBytes = usableDiskBytes;
        }

        @Override public boolean isOfficialSupportedPaper() { return officialPaper; }
        @Override public boolean isWorldLoaded(String name) { return worldLoaded; }
        @Override public boolean hasPreparedDem() { return preparedDem; }
        @Override public Path serverRoot() { return serverRoot; }
        @Override public Path worldContainer() { return serverRoot.resolve("world"); }
        @Override public long usableDiskBytes(Path path) { return usableDiskBytes; }
    }
}
