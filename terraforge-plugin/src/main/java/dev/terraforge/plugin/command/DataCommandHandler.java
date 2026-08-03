package dev.terraforge.plugin.command;

import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;

/**
 * Routes {@code /earth data status} to a {@link DataCommandContext} snapshot of the prepared DEM
 * inventory. Never scans the DEM directory synchronously: tile count/coverage/bathymetry come from
 * an in-memory catalogue built at startup, and the corruption count comes from a cache that is only
 * ever updated by a background scan (see {@code DemCorruptionCache}).
 */
public final class DataCommandHandler implements EarthSubcommand {
    static final String PERMISSION = "terraforge.command.data";
    private static final List<String> VERBS = List.of("status");

    private final DataCommandContext context;

    public DataCommandHandler(DataCommandContext context) {
        this.context = context;
    }

    @Override
    public CommandResult execute(CommandSender sender, List<String> args) {
        if (!sender.hasPermission(PERMISSION)) return CommandResult.denied();
        if (args.size() != 1 || !"status".equals(args.get(0).toLowerCase(Locale.ROOT))) return usage();
        return status();
    }

    @Override
    public List<String> suggest(CommandSender sender, List<String> args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.size() != 1) return List.of();
        String prefix = args.get(0).toLowerCase(Locale.ROOT);
        return VERBS.stream().filter(verb -> verb.startsWith(prefix)).toList();
    }

    @Override
    public boolean isVisibleTo(CommandSender sender) {
        return sender.hasPermission(PERMISSION);
    }

    private CommandResult status() {
        CommandResult.Builder builder = CommandResult.builder();
        builder.line(CommandResult.Level.INFO, "Tiles: " + context.tileCount() + " prepared"
                + (context.hasBathymetry() ? " (with bathymetry)" : ", land only"));
        builder.line(CommandResult.Level.INFO, "Coverage: " + context.coverageDescription());
        builder.line(CommandResult.Level.INFO, "Corruption: " + context.cachedCorruptFileCount()
                + " unreadable file(s) as of the last background scan");
        builder.line(CommandResult.Level.INFO, "Missing requested tiles: " + context.missingRequestedTileCount());
        context.refreshCorruptionAsync();
        return builder.build();
    }

    private CommandResult usage() {
        return CommandResult.warning("Usage: /earth data status");
    }
}
