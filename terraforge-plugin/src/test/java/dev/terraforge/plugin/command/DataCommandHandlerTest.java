package dev.terraforge.plugin.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/** Parser/permission/output tests for {@code /earth data status}. */
class DataCommandHandlerTest {

    @Test void deniesExecutionWithoutPermission() {
        DataCommandHandler handler = new DataCommandHandler(context());
        CommandResult result = handler.execute(sender(false), List.of("status"));
        assertThat(result.isError()).isTrue();
    }

    @Test void deniesTabCompletionWithoutPermission() {
        DataCommandHandler handler = new DataCommandHandler(context());
        assertThat(handler.suggest(sender(false), List.of("s"))).isEmpty();
        assertThat(handler.isVisibleTo(sender(false))).isFalse();
    }

    @Test void reportsUsageForMissingArgs() {
        DataCommandHandler handler = new DataCommandHandler(context());
        CommandResult result = handler.execute(sender(true), List.of());
        assertThat(result.lines().get(0).text()).isEqualTo("Usage: /earth data status");
    }

    @Test void reportsUsageForUnknownVerb() {
        DataCommandHandler handler = new DataCommandHandler(context());
        CommandResult result = handler.execute(sender(true), List.of("bogus"));
        assertThat(result.lines().get(0).text()).isEqualTo("Usage: /earth data status");
    }

    @Test void suggestsStatusFilteredByPrefix() {
        DataCommandHandler handler = new DataCommandHandler(context());
        assertThat(handler.suggest(sender(true), List.of("st"))).containsExactly("status");
        assertThat(handler.suggest(sender(true), List.of("x"))).isEmpty();
    }

    @Test void statusReportsTilesCoverageCorruptionAndMissingTiles() {
        FakeContext ctx = new FakeContext();
        ctx.tileCount = 42;
        ctx.coverage = "[10,10 -> 20,20]";
        ctx.bathymetry = true;
        ctx.missing = 3;
        ctx.corrupt = 2;
        DataCommandHandler handler = new DataCommandHandler(ctx);

        CommandResult result = handler.execute(sender(true), List.of("status"));

        List<String> lines = result.lines().stream().map(CommandResult.Line::text).toList();
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("42 prepared").contains("bathymetry"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("[10,10 -> 20,20]"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("2 unreadable"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("Missing requested tiles: 3"));
    }

    @Test void statusTriggersAnAsynchronousRefreshWithoutBlocking() {
        FakeContext ctx = new FakeContext();
        DataCommandHandler handler = new DataCommandHandler(ctx);

        handler.execute(sender(true), List.of("status"));

        assertThat(ctx.refreshCalls.get()).isEqualTo(1);
    }

    private static CommandSender sender(boolean permitted) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(permitted);
        return sender;
    }

    private DataCommandContext context() {
        return new FakeContext();
    }

    private static final class FakeContext implements DataCommandContext {
        int tileCount;
        String coverage = "none";
        boolean bathymetry;
        int missing;
        int corrupt;
        final AtomicInteger refreshCalls = new AtomicInteger();

        @Override public int tileCount() { return tileCount; }
        @Override public String coverageDescription() { return coverage; }
        @Override public boolean hasBathymetry() { return bathymetry; }
        @Override public int missingRequestedTileCount() { return missing; }
        @Override public int cachedCorruptFileCount() { return corrupt; }
        @Override public void refreshCorruptionAsync() { refreshCalls.incrementAndGet(); }
    }
}
