package dev.terraforge.plugin.command;

import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;

/** Routes {@code /earth performance} to a live snapshot of server and pregeneration health. */
public final class PerformanceCommandHandler implements EarthSubcommand {
    static final String PERMISSION = "terraforge.command.performance";

    private final PerformanceCommandContext context;

    public PerformanceCommandHandler(PerformanceCommandContext context) {
        this.context = context;
    }

    @Override
    public CommandResult execute(CommandSender sender, List<String> args) {
        if (!sender.hasPermission(PERMISSION)) return CommandResult.denied();
        if (!args.isEmpty()) return usage();
        CommandResult.Builder builder = CommandResult.builder();
        builder.line(CommandResult.Level.INFO, "TPS: " + fmt(context.tps()) + "  MSPT: " + fmt(context.mspt())
                + "  Online players: " + context.onlinePlayers() + "  Usable disk: "
                + context.usableDiskGb() + " GB");
        builder.line(CommandResult.Level.INFO, "Pregeneration: state="
                + context.pregenerationState().orElse("none") + " in-flight="
                + context.pregenerationInFlight().map(String::valueOf).orElse("n/a"));
        return builder.build();
    }

    @Override
    public List<String> suggest(CommandSender sender, List<String> args) {
        return List.of();
    }

    @Override
    public boolean isVisibleTo(CommandSender sender) {
        return sender.hasPermission(PERMISSION);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private CommandResult usage() {
        return CommandResult.warning("Usage: /earth performance");
    }
}
