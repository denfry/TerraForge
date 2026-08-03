package dev.terraforge.plugin.command;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/**
 * Structured outcome of one command dispatch: a sequence of lines, each carrying its own severity.
 *
 * <p>Handlers build a {@code CommandResult} instead of writing to {@link CommandSender} directly, so
 * parser/permission/output tests can assert on the returned content without a running server. The
 * router is what finally calls {@link #sendTo}, translating each line into a coloured
 * {@link Component}.
 */
public record CommandResult(List<Line> lines) {

    /** No lines: the command was handled elsewhere (see {@code EarthCommandRouter}'s legacy fallback). */
    public static final CommandResult NONE = new CommandResult(List.of());

    public CommandResult {
        lines = List.copyOf(lines);
    }

    public enum Level { INFO, SUCCESS, WARNING, ERROR }

    public record Line(String text, Level level) {}

    public static CommandResult of(Level level, String text) {
        return new CommandResult(List.of(new Line(text, level)));
    }

    public static CommandResult info(String text) { return of(Level.INFO, text); }
    public static CommandResult success(String text) { return of(Level.SUCCESS, text); }
    public static CommandResult warning(String text) { return of(Level.WARNING, text); }
    public static CommandResult error(String text) { return of(Level.ERROR, text); }

    public static CommandResult denied() {
        return error("You do not have permission for this command.");
    }

    public static CommandResult multiline(List<Line> lines) {
        return new CommandResult(lines);
    }

    public boolean isError() {
        return lines.stream().anyMatch(line -> line.level() == Level.ERROR);
    }

    /** Builder used by handlers that assemble several lines of mixed severity, such as check reports. */
    public static final class Builder {
        private final List<Line> lines = new ArrayList<>();

        public Builder line(Level level, String text) {
            lines.add(new Line(text, level));
            return this;
        }

        public CommandResult build() {
            return new CommandResult(lines);
        }
    }

    public static Builder builder() { return new Builder(); }

    public void sendTo(CommandSender sender) {
        for (Line line : lines) {
            sender.sendMessage(Component.text(line.text(), colorFor(line.level())));
        }
    }

    private static NamedTextColor colorFor(Level level) {
        return switch (level) {
            case INFO -> NamedTextColor.GRAY;
            case SUCCESS -> NamedTextColor.GREEN;
            case WARNING -> NamedTextColor.YELLOW;
            case ERROR -> NamedTextColor.RED;
        };
    }
}
