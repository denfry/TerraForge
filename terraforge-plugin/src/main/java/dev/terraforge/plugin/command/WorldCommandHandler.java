package dev.terraforge.plugin.command;

import dev.terraforge.plugin.world.WorldCreationCheck;
import dev.terraforge.plugin.world.WorldCreationPlan;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

/**
 * Routes {@code /earth world plan|create|status|verify|abort} to {@link dev.terraforge.plugin.world.ManagedWorldService}.
 *
 * <p>{@code verify} has no direct one-call analogue on the service beyond {@link
 * dev.terraforge.plugin.world.ManagedWorldService#verify}, which was added alongside this handler: it
 * re-runs the exact startup-verification path on demand rather than duplicating its logic here.
 */
public final class WorldCommandHandler implements EarthSubcommand {
    static final String PERMISSION = "terraforge.command.world";
    private static final List<String> VERBS = List.of("plan", "create", "status", "verify", "abort");

    private final WorldCommandContext context;

    public WorldCommandHandler(WorldCommandContext context) {
        this.context = context;
    }

    @Override
    public CommandResult execute(CommandSender sender, List<String> args) {
        if (!sender.hasPermission(PERMISSION)) return CommandResult.denied();
        if (args.size() != 1) return usage();
        return switch (args.get(0).toLowerCase(Locale.ROOT)) {
            case "plan" -> plan(sender);
            case "create" -> create(sender);
            case "status" -> status(sender);
            case "verify" -> verify(sender);
            case "abort" -> abort(sender);
            default -> usage();
        };
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

    private CommandResult plan(CommandSender sender) {
        try {
            WorldCreationPlan plan = buildPlan();
            return planReport(plan);
        } catch (RuntimeException exception) {
            return sanitize(sender, exception, "Planning");
        }
    }

    private CommandResult create(CommandSender sender) {
        try {
            WorldCreationPlan plan = buildPlan();
            if (!plan.executable()) {
                CommandResult.Builder builder = CommandResult.builder();
                builder.line(CommandResult.Level.ERROR, "Cannot stage the managed Earth world: not every prerequisite passed.");
                appendChecks(builder, plan);
                return builder.build();
            }
            context.service().stage(plan);
            return CommandResult.success("Managed Earth world staged; restart the server to create it.");
        } catch (IOException | RuntimeException exception) {
            return sanitize(sender, exception, "Staging");
        }
    }

    private CommandResult status(CommandSender sender) {
        try {
            return CommandResult.info("Managed Earth: " + context.service().status());
        } catch (IOException | RuntimeException exception) {
            return sanitize(sender, exception, "Reading managed Earth status");
        }
    }

    private CommandResult verify(CommandSender sender) {
        try {
            List<String> failures = new ArrayList<>();
            boolean ready = context.service().verify(context.liveSnapshotFactory(), failures::add);
            if (ready) return CommandResult.success("Managed Earth world verified against the live server.");
            CommandResult.Builder builder = CommandResult.builder();
            builder.line(CommandResult.Level.ERROR, "Managed Earth world failed verification:");
            failures.forEach(failure -> builder.line(CommandResult.Level.WARNING, "- " + failure));
            return builder.build();
        } catch (IOException | RuntimeException exception) {
            return sanitize(sender, exception, "Verifying the managed Earth world");
        }
    }

    private CommandResult abort(CommandSender sender) {
        try {
            context.service().abort(context.serverRoot(), context.worldContainer());
            return CommandResult.success("Staged managed Earth world removed.");
        } catch (IOException | RuntimeException exception) {
            return sanitize(sender, exception, "Aborting the staged managed Earth world");
        }
    }

    private WorldCreationPlan buildPlan() {
        return context.service().plan(context.environment(), context.configuredWorldName(), context.minimumFreeDiskGb(),
                context.verticalProfile(), context.demDirectory(), context.chunkSettings());
    }

    private static CommandResult planReport(WorldCreationPlan plan) {
        CommandResult.Builder builder = CommandResult.builder();
        appendChecks(builder, plan);
        builder.line(plan.executable() ? CommandResult.Level.SUCCESS : CommandResult.Level.ERROR,
                plan.executable()
                        ? "Plan is executable; run /earth world create to stage it."
                        : "Plan is not executable; resolve the failing checks above.");
        return builder.build();
    }

    private static void appendChecks(CommandResult.Builder builder, WorldCreationPlan plan) {
        for (WorldCreationCheck check : plan.checks()) {
            builder.line(check.passed() ? CommandResult.Level.SUCCESS : CommandResult.Level.WARNING,
                    (check.passed() ? "[ok] " : "[fail] ") + check.name() + ": " + check.detail());
        }
    }

    /**
     * Console operators already have full filesystem access and read TerraForge's own logs, so seeing
     * an exception's message there is not a disclosure; every other sender (players, command blocks,
     * plugins invoking the command programmatically) gets a generic pointer to the console log instead,
     * so a stack trace or a server-root path is never echoed into chat.
     */
    private static CommandResult sanitize(CommandSender sender, Exception exception, String action) {
        if (sender instanceof ConsoleCommandSender) {
            return CommandResult.error(action + " failed: " + exception.getMessage());
        }
        return CommandResult.error(action + " failed; see the console log for details.");
    }

    private CommandResult usage() {
        return CommandResult.warning("Usage: /earth world <" + String.join("|", VERBS) + ">");
    }
}
