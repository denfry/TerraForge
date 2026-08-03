package dev.terraforge.plugin.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.terraforge.plugin.pregen.ChunkGenerationPort;
import dev.terraforge.plugin.pregen.PregenerationCheckpointStore;
import dev.terraforge.plugin.pregen.PregenerationController;
import dev.terraforge.plugin.pregen.PregenerationSpec;
import dev.terraforge.plugin.pregen.PregenerationState;
import dev.terraforge.plugin.pregen.ServerHealthPolicy;
import dev.terraforge.plugin.pregen.ServerHealthSnapshot;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parser/permission/output tests for {@code /earth pregenerate ...}, driven entirely against a real
 * {@link PregenerationController} wired to a synchronous executor and a lenient always-healthy
 * policy -- the health/fingerprint rejections under test belong to the handler itself, not the
 * controller, so the controller's own gates stay out of the way.
 */
class PregenerationCommandHandlerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);
    private static final Logger LOGGER = Logger.getLogger("PregenerationCommandHandlerTest");

    @TempDir Path directory;

    @Test void deniesExecutionWithoutPermission() {
        PregenerationCommandHandler handler = new PregenerationCommandHandler(context());
        CommandResult result = handler.execute(sender(false), List.of("status"));
        assertThat(result.isError()).isTrue();
        assertThat(result.lines()).extracting(CommandResult.Line::text)
                .containsExactly("You do not have permission for this command.");
    }

    @Test void deniesTabCompletionWithoutPermission() {
        PregenerationCommandHandler handler = new PregenerationCommandHandler(context());
        assertThat(handler.suggest(sender(false), List.of("s"))).isEmpty();
        assertThat(handler.isVisibleTo(sender(false))).isFalse();
    }

    @Test void reportsUsageForMissingArgs() {
        PregenerationCommandHandler handler = new PregenerationCommandHandler(context());
        CommandResult result = handler.execute(sender(true), List.of());
        assertThat(result.lines().get(0).text()).startsWith("Usage: /earth pregenerate <");
    }

    @Test void reportsUsageForUnknownVerb() {
        PregenerationCommandHandler handler = new PregenerationCommandHandler(context());
        CommandResult result = handler.execute(sender(true), List.of("bogus"));
        assertThat(result.lines().get(0).text()).startsWith("Usage: /earth pregenerate <");
    }

    @Test void suggestsVerbsFilteredByPrefix() {
        PregenerationCommandHandler handler = new PregenerationCommandHandler(context());
        assertThat(handler.suggest(sender(true), List.of("st"))).containsExactlyInAnyOrder("start", "status");
        assertThat(handler.suggest(sender(true), List.of("f"))).containsExactly("full");
        assertThat(handler.suggest(sender(true), List.of("full", "con"))).containsExactly("confirm");
    }

    @Test void startRejectsNonNumericRadius() {
        PregenerationCommandHandler handler = new PregenerationCommandHandler(context());
        CommandResult result = handler.execute(sender(true), List.of("start", "not-a-number"));
        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(0).text()).contains("whole number");
    }

    @Test void startRejectsNegativeRadius() {
        PregenerationCommandHandler handler = new PregenerationCommandHandler(context());
        CommandResult result = handler.execute(sender(true), List.of("start", "-5"));
        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(0).text()).contains("Cannot start pregeneration");
    }

    @Test void startRejectsOverflowingBounds() {
        PregenerationCommandHandler handler = new PregenerationCommandHandler(context());
        CommandResult result = handler.execute(sender(true),
                List.of("start", "10", String.valueOf(Integer.MAX_VALUE), "0"));
        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(0).text()).contains("Cannot start pregeneration");
    }

    @Test void startRejectsMalformedCenter() {
        PregenerationCommandHandler handler = new PregenerationCommandHandler(context());
        CommandResult result = handler.execute(sender(true), List.of("start", "10", "abc", "0"));
        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(0).text()).contains("whole numbers");
    }

    @Test void startRejectsWrongArgumentCount() {
        PregenerationCommandHandler handler = new PregenerationCommandHandler(context());
        CommandResult result = handler.execute(sender(true), List.of("start", "10", "1"));
        assertThat(result.lines().get(0).text()).startsWith("Usage: /earth pregenerate <");
    }

    @Test void startRejectsWhenManagedWorldNotReady() {
        FakeContext ctx = context();
        ctx.managedWorldReady = false;
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);
        CommandResult result = handler.execute(sender(true), List.of("start", "16"));
        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(0).text()).contains("not ready");
    }

    @Test void startSucceedsAndCreatesAPausedJob() {
        FakeContext ctx = context();
        ctx.controller = controller();
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);

        CommandResult result = handler.execute(sender(true), List.of("start", "16"));

        assertThat(result.isError()).isFalse();
        assertThat(ctx.controller.status().orElseThrow().state()).isEqualTo(PregenerationState.PAUSED);
    }

    @Test void startRejectsADuplicateActiveJob() {
        FakeContext ctx = context();
        ctx.controller = controller();
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);
        handler.execute(sender(true), List.of("start", "16"));

        CommandResult result = handler.execute(sender(true), List.of("start", "32"));

        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(0).text()).contains("already active");
    }

    @Test void fullRejectsWithoutLiteralConfirm() {
        FakeContext ctx = context();
        ctx.controller = controller();
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);

        CommandResult result = handler.execute(sender(true), List.of("full"));

        assertThat(result.isError()).isFalse(); // usage warning, not an error result
        assertThat(result.lines().get(0).text()).contains("literal 'confirm' is required");
        assertThat(ctx.controller.status()).isEmpty();
    }

    @Test void fullRejectsWithWrongLiteral() {
        FakeContext ctx = context();
        ctx.controller = controller();
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);

        CommandResult result = handler.execute(sender(true), List.of("full", "yes"));

        assertThat(ctx.controller.status()).isEmpty();
        assertThat(result.lines().get(0).text()).contains("confirm");
    }

    @Test void fullSucceedsWithConfirmUsingTheFullRegionSpec() {
        FakeContext ctx = context();
        ctx.controller = controller();
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);

        CommandResult result = handler.execute(sender(true), List.of("full", "confirm"));

        assertThat(result.isError()).isFalse();
        assertThat(ctx.controller.status().orElseThrow().spec()).isEqualTo(ctx.fullSpec);
    }

    @Test void resumeRejectsWhenNoJobExists() {
        FakeContext ctx = context();
        ctx.controller = controller();
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);

        CommandResult result = handler.execute(sender(true), List.of("resume"));

        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(0).text()).contains("No pregeneration job exists");
    }

    @Test void resumeRejectsOnConfigFingerprintMismatch() {
        FakeContext ctx = context();
        ctx.controller = controller();
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);
        handler.execute(sender(true), List.of("start", "16"));
        ctx.configFingerprint = "c".repeat(64); // drifted after the job was created

        CommandResult result = handler.execute(sender(true), List.of("resume"));

        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(0).text()).contains("fingerprint no longer matches");
        assertThat(ctx.controller.status().orElseThrow().state()).isEqualTo(PregenerationState.PAUSED);
    }

    @Test void resumeRejectsOnDataFingerprintMismatch() {
        FakeContext ctx = context();
        ctx.controller = controller();
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);
        handler.execute(sender(true), List.of("start", "16"));
        ctx.dataFingerprint = "c".repeat(64);

        CommandResult result = handler.execute(sender(true), List.of("resume"));

        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(0).text()).contains("fingerprint no longer matches");
    }

    @Test void resumeRejectsWhenDemCoverageIsUnavailable() {
        FakeContext ctx = context();
        ctx.controller = controller();
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);
        handler.execute(sender(true), List.of("start", "16"));
        ctx.health = new ServerHealthSnapshot(0, 20, 20, 20, false, false, false);

        CommandResult result = handler.execute(sender(true), List.of("resume"));

        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(0).text()).contains("DEM coverage");
    }

    @Test void resumeRejectsWhenUsableDiskIsBelowReserve() {
        FakeContext ctx = context();
        ctx.controller = controller();
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);
        handler.execute(sender(true), List.of("start", "16"));
        ctx.health = new ServerHealthSnapshot(0, 20, 20, 1, true, false, false);
        ctx.reserveDiskGb = 10;

        CommandResult result = handler.execute(sender(true), List.of("resume"));

        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(0).text()).contains("disk space");
    }

    @Test void resumeSucceedsAndTransitionsToRunning() {
        FakeContext ctx = context();
        ctx.controller = controller();
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);
        handler.execute(sender(true), List.of("start", "16"));

        CommandResult result = handler.execute(sender(true), List.of("resume"));

        assertThat(result.isError()).isFalse();
        assertThat(ctx.controller.status().orElseThrow().state()).isEqualTo(PregenerationState.RUNNING);
    }

    @Test void pauseTransitionsARunningJobBackToPaused() {
        FakeContext ctx = context();
        ctx.controller = controller();
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);
        handler.execute(sender(true), List.of("start", "16"));
        handler.execute(sender(true), List.of("resume"));

        CommandResult result = handler.execute(sender(true), List.of("pause"));

        assertThat(result.isError()).isFalse();
        assertThat(ctx.controller.status().orElseThrow().state()).isEqualTo(PregenerationState.PAUSED);
    }

    @Test void cancelRemovesTheJob() {
        FakeContext ctx = context();
        ctx.controller = controller();
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);
        handler.execute(sender(true), List.of("start", "16"));

        CommandResult result = handler.execute(sender(true), List.of("cancel"));

        assertThat(result.isError()).isFalse();
        assertThat(ctx.controller.status().orElseThrow().state()).isEqualTo(PregenerationState.CANCELLED);
    }

    @Test void pauseResumeStatusCancelRejectExtraArguments() {
        FakeContext ctx = context();
        ctx.controller = controller();
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);
        for (String verb : List.of("pause", "resume", "status", "cancel")) {
            CommandResult result = handler.execute(sender(true), List.of(verb, "extra"));
            assertThat(result.lines().get(0).text()).startsWith("Usage: /earth pregenerate <");
        }
    }

    @Test void statusReportsNoJobYet() {
        FakeContext ctx = context();
        ctx.controller = controller();
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);

        CommandResult result = handler.execute(sender(true), List.of("status"));

        assertThat(result.lines().get(0).text()).isEqualTo("No pregeneration job has been created yet.");
        assertThat(result.lines()).anySatisfy(line -> assertThat(line.text()).startsWith("Health:"));
    }

    @Test void statusReportsEveryRequiredField() {
        FakeContext ctx = context();
        ctx.controller = controller();
        ctx.reserveDiskGb = 10;
        ctx.pauseWhenPlayersOnline = true;
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);
        handler.execute(sender(true), List.of("start", "16", "5", "7"));

        CommandResult result = handler.execute(sender(true), List.of("status"));

        List<String> lines = result.lines().stream().map(CommandResult.Line::text).toList();
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("State:").contains("PAUSED"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("completed=0").contains("skipped=0")
                .contains("failed=0").contains("total=").contains("in-flight=0"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("center=(5, 7)").contains("radius=16"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("Checkpoint age:"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("tps=").contains("mspt=")
                .contains("usableDiskGb=").contains("reserveGb=10").contains("onlinePlayerPolicy="));
    }

    @Test void statusUnavailableWhenManagedWorldIsNotReady() {
        FakeContext ctx = context();
        ctx.managedWorldReady = false;
        PregenerationCommandHandler handler = new PregenerationCommandHandler(ctx);
        CommandResult result = handler.execute(sender(true), List.of("status"));
        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(0).text()).contains("unavailable");
    }

    // --- fixtures -------------------------------------------------------

    private static CommandSender sender(boolean permitted) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(permitted);
        return sender;
    }

    private FakeContext context() {
        return new FakeContext();
    }

    private PregenerationController controller() {
        // Never completes: assertions only inspect checkpoint state as of the moment resume()/pause()
        // return, before any chunk future would complete. A self-completing future here would make the
        // synchronous (Runnable::run) executor re-enter PregenerationController#pump() while it is still
        // on the call stack -- a pre-existing quirk of that class outside the scope of this handler.
        var port = new ChunkGenerationPort() {
            @Override public boolean isChunkGenerated(int chunkX, int chunkZ) { return false; }
            @Override public CompletableFuture<Boolean> loadOrGenerate(int chunkX, int chunkZ) {
                return new CompletableFuture<>();
            }
        };
        var store = new PregenerationCheckpointStore(directory.resolve("job-" + System.nanoTime()));
        var lenientPolicy = new ServerHealthPolicy(false, 0, 1000, 0, 0);
        return new PregenerationController(port, lenientPolicy,
                () -> new ServerHealthSnapshot(0, 20, 20, 20, true, false, false),
                store, Runnable::run, CLOCK, 4, 999, null, LOGGER);
    }

    private static final class FakeContext implements PregenerationCommandContext {
        boolean managedWorldReady = true;
        PregenerationController controller;
        PregenerationSpec fullSpec = PregenerationSpec.around(100, 200, 50);
        String configFingerprint = "a".repeat(64);
        String dataFingerprint = "b".repeat(64);
        ServerHealthSnapshot health = new ServerHealthSnapshot(0, 20, 20, 20, true, false, false);
        long reserveDiskGb = 10;
        boolean pauseWhenPlayersOnline = true;

        @Override public boolean managedWorldReady() { return managedWorldReady; }
        @Override public Optional<PregenerationController> controller() { return Optional.ofNullable(controller); }
        @Override public PregenerationSpec fullRegionSpec() { return fullSpec; }
        @Override public String currentConfigFingerprint() { return configFingerprint; }
        @Override public String currentDataFingerprint() { return dataFingerprint; }
        @Override public ServerHealthSnapshot currentHealth() { return health; }
        @Override public long reserveDiskGb() { return reserveDiskGb; }
        @Override public boolean pauseWhenPlayersOnline() { return pauseWhenPlayersOnline; }
        @Override public Clock clock() { return CLOCK; }
    }
}
