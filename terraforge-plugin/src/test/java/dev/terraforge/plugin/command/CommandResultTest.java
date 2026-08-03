package dev.terraforge.plugin.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class CommandResultTest {
    @Test void deniedIsAnErrorLine() {
        CommandResult result = CommandResult.denied();
        assertThat(result.isError()).isTrue();
        assertThat(result.lines()).extracting(CommandResult.Line::text)
                .containsExactly("You do not have permission for this command.");
    }

    @Test void noneHasNoLinesAndIsNotAnError() {
        assertThat(CommandResult.NONE.lines()).isEmpty();
        assertThat(CommandResult.NONE.isError()).isFalse();
    }

    @Test void builderPreservesLineOrderAndSeverity() {
        CommandResult result = CommandResult.builder()
                .line(CommandResult.Level.SUCCESS, "ok")
                .line(CommandResult.Level.ERROR, "bad")
                .build();
        assertThat(result.isError()).isTrue();
        assertThat(result.lines()).extracting(CommandResult.Line::text).containsExactly("ok", "bad");
    }

    @Test void sendToDeliversOneMessagePerLine() {
        CommandSender sender = mock(CommandSender.class);
        CommandResult result = CommandResult.builder()
                .line(CommandResult.Level.INFO, "one")
                .line(CommandResult.Level.WARNING, "two")
                .build();

        result.sendTo(sender);

        verify(sender, org.mockito.Mockito.times(2)).sendMessage(any(Component.class));
    }
}
