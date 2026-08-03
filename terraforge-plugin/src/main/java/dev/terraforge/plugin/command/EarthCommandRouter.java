package dev.terraforge.plugin.command;

import dev.terraforge.plugin.EarthCommand;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.CommandSender;

/**
 * Top-level {@code /earth} dispatcher.
 *
 * <p>A small, growable map of feature families ({@code world}, {@code pregenerate}, {@code data},
 * {@code doctor}, {@code performance}) is tried first. Anything the map does not recognise falls back
 * to the legacy {@link EarthCommand}, which still owns the geographic commands and the
 * not-yet-migrated admin verbs. The legacy path sends its own messages directly to the sender (as it
 * always has), so it returns {@link CommandResult#NONE} here rather than a duplicate copy of what it
 * already sent.
 */
public final class EarthCommandRouter {
    private final EarthCommand legacy;
    private final Map<String, EarthSubcommand> families;

    public EarthCommandRouter(EarthCommand legacy, WorldCommandHandler worldHandler,
            PregenerationCommandHandler pregenerationHandler, DataCommandHandler dataHandler,
            DoctorCommandHandler doctorHandler, PerformanceCommandHandler performanceHandler) {
        this.legacy = legacy;
        Map<String, EarthSubcommand> map = new LinkedHashMap<>();
        map.put("world", worldHandler);
        map.put("pregenerate", pregenerationHandler);
        map.put("data", dataHandler);
        map.put("doctor", doctorHandler);
        map.put("performance", performanceHandler);
        this.families = Map.copyOf(map);
    }

    public CommandResult execute(CommandSender sender, List<String> args) {
        if (!args.isEmpty()) {
            EarthSubcommand handler = families.get(args.get(0).toLowerCase(Locale.ROOT));
            if (handler != null) {
                return handler.execute(sender, args.subList(1, args.size()));
            }
        }
        legacy.dispatch(sender, args.toArray(new String[0]));
        return CommandResult.NONE;
    }

    public List<String> suggest(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            return List.of();
        }
        if (args.size() == 1) {
            List<String> options = new ArrayList<>(legacy.completions(sender, args.toArray(new String[0])));
            String prefix = args.get(0).toLowerCase(Locale.ROOT);
            for (var entry : families.entrySet()) {
                if (entry.getKey().startsWith(prefix) && entry.getValue().isVisibleTo(sender)) {
                    options.add(entry.getKey());
                }
            }
            return options;
        }
        EarthSubcommand handler = families.get(args.get(0).toLowerCase(Locale.ROOT));
        if (handler != null) {
            return handler.suggest(sender, args.subList(1, args.size()));
        }
        return legacy.completions(sender, args.toArray(new String[0]));
    }
}
