package dev.terraforge.plugin.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/** Parser/permission/output tests for {@code /earth performance}. */
class PerformanceCommandHandlerTest {

    @Test void deniesExecutionWithoutPermission() {
        PerformanceCommandHandler handler = new PerformanceCommandHandler(context(19.8, 45.0, 3, 120,
                Optional.of(2), Optional.of("RUNNING")));
        CommandResult result = handler.execute(sender(false), List.of());
        assertThat(result.isError()).isTrue();
    }

    @Test void deniesTabCompletionWithoutPermission() {
        PerformanceCommandHandler handler = new PerformanceCommandHandler(context(20, 40, 0, 100,
                Optional.empty(), Optional.empty()));
        assertThat(handler.isVisibleTo(sender(false))).isFalse();
    }

    @Test void reportsUsageForExtraArgs() {
        PerformanceCommandHandler handler = new PerformanceCommandHandler(context(20, 40, 0, 100,
                Optional.empty(), Optional.empty()));
        CommandResult result = handler.execute(sender(true), List.of("extra"));
        assertThat(result.lines().get(0).text()).isEqualTo("Usage: /earth performance");
    }

    @Test void reportsTpsMsptPlayersDiskAndPregenerationState() {
        PerformanceCommandHandler handler = new PerformanceCommandHandler(context(19.8, 45.2, 3, 120,
                Optional.of(2), Optional.of("RUNNING")));

        CommandResult result = handler.execute(sender(true), List.of());

        List<String> lines = result.lines().stream().map(CommandResult.Line::text).toList();
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("TPS: 19.8").contains("MSPT: 45.2")
                .contains("Online players: 3").contains("Usable disk: 120 GB"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("state=RUNNING").contains("in-flight=2"));
    }

    @Test void reportsNoneAndNaWhenNoPregenerationJobExists() {
        PerformanceCommandHandler handler = new PerformanceCommandHandler(context(20, 30, 0, 200,
                Optional.empty(), Optional.empty()));

        CommandResult result = handler.execute(sender(true), List.of());

        assertThat(result.lines().get(1).text()).contains("state=none").contains("in-flight=n/a");
    }

    private static CommandSender sender(boolean permitted) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(permitted);
        return sender;
    }

    private PerformanceCommandContext context(double tps, double mspt, int players, long diskGb,
            Optional<Integer> inFlight, Optional<String> state) {
        return new PerformanceCommandContext() {
            @Override public double tps() { return tps; }
            @Override public double mspt() { return mspt; }
            @Override public int onlinePlayers() { return players; }
            @Override public long usableDiskGb() { return diskGb; }
            @Override public Optional<Integer> pregenerationInFlight() { return inFlight; }
            @Override public Optional<String> pregenerationState() { return state; }
        };
    }
}
