package dev.terraforge.plugin.command;

import dev.terraforge.plugin.world.WorldCreationCheck;
import java.util.List;
import org.bukkit.command.CommandSender;

/** Routes {@code /earth doctor} to a list of independently reportable diagnostics. */
public final class DoctorCommandHandler implements EarthSubcommand {
    static final String PERMISSION = "terraforge.command.doctor";

    private final DoctorCommandContext context;

    public DoctorCommandHandler(DoctorCommandContext context) {
        this.context = context;
    }

    @Override
    public CommandResult execute(CommandSender sender, List<String> args) {
        if (!sender.hasPermission(PERMISSION)) return CommandResult.denied();
        if (!args.isEmpty()) return usage();
        try {
            List<WorldCreationCheck> checks = context.diagnose();
            CommandResult.Builder builder = CommandResult.builder();
            for (WorldCreationCheck check : checks) {
                builder.line(check.passed() ? CommandResult.Level.SUCCESS : CommandResult.Level.WARNING,
                        (check.passed() ? "[ok] " : "[fail] ") + check.name() + ": " + check.detail());
            }
            boolean allPassed = checks.stream().allMatch(WorldCreationCheck::passed);
            builder.line(allPassed ? CommandResult.Level.SUCCESS : CommandResult.Level.ERROR,
                    allPassed ? "TerraForge is healthy." : "TerraForge found problems; see above.");
            return builder.build();
        } catch (RuntimeException exception) {
            return CommandResult.sanitizedError(sender, exception, "Running diagnostics");
        }
    }

    @Override
    public List<String> suggest(CommandSender sender, List<String> args) {
        return List.of();
    }

    @Override
    public boolean isVisibleTo(CommandSender sender) {
        return sender.hasPermission(PERMISSION);
    }

    private CommandResult usage() {
        return CommandResult.warning("Usage: /earth doctor");
    }
}
