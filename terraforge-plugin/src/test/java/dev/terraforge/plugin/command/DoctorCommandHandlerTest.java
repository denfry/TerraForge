package dev.terraforge.plugin.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.terraforge.plugin.world.WorldCreationCheck;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/** Parser/permission/output tests for {@code /earth doctor}. */
class DoctorCommandHandlerTest {

    @Test void deniesExecutionWithoutPermission() {
        DoctorCommandHandler handler = new DoctorCommandHandler(List::of);
        CommandResult result = handler.execute(sender(false), List.of());
        assertThat(result.isError()).isTrue();
    }

    @Test void deniesTabCompletionWithoutPermission() {
        DoctorCommandHandler handler = new DoctorCommandHandler(List::of);
        assertThat(handler.isVisibleTo(sender(false))).isFalse();
    }

    @Test void reportsUsageForExtraArgs() {
        DoctorCommandHandler handler = new DoctorCommandHandler(List::of);
        CommandResult result = handler.execute(sender(true), List.of("extra"));
        assertThat(result.lines().get(0).text()).isEqualTo("Usage: /earth doctor");
    }

    @Test void reportsEveryCheckAndAHealthySummaryWhenAllPass() {
        DoctorCommandContext context = () -> List.of(
                new WorldCreationCheck("managed-world", true, "verified"),
                new WorldCreationCheck("dem-data", true, "10 tiles"));
        DoctorCommandHandler handler = new DoctorCommandHandler(context);

        CommandResult result = handler.execute(sender(true), List.of());

        assertThat(result.isError()).isFalse();
        List<String> lines = result.lines().stream().map(CommandResult.Line::text).toList();
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("[ok] managed-world: verified"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("[ok] dem-data: 10 tiles"));
        assertThat(lines.get(lines.size() - 1)).contains("healthy");
    }

    @Test void reportsFailingChecksAndAnUnhealthySummary() {
        DoctorCommandContext context = () -> List.of(
                new WorldCreationCheck("managed-world", false, "not verified"));
        DoctorCommandHandler handler = new DoctorCommandHandler(context);

        CommandResult result = handler.execute(sender(true), List.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.lines().get(0).text()).contains("[fail] managed-world: not verified");
        assertThat(result.lines().get(result.lines().size() - 1).text()).contains("found problems");
    }

    private static CommandSender sender(boolean permitted) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(permitted);
        return sender;
    }
}
