package dev.terraforge.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Arrays;
import java.util.Collection;
import org.bukkit.command.CommandSender;

/**
 * Adapts {@link EarthCommandRouter} to Paper's dynamic {@link BasicCommand} registration.
 *
 * <p>No root {@link #permission()} is declared: the geographic commands (whereami, coords, info, ...)
 * default to {@code true} in {@code plugin.yml} so every player can use them, and gating the whole
 * command node here would hide that read-only surface from non-operators before Bukkit's own
 * per-verb permission checks ever run. Each family enforces its own permission instead -- {@code
 * WorldCommandHandler} on {@code terraforge.command.world}, the legacy verbs on their existing nodes.
 */
public final class PaperEarthCommand implements BasicCommand {
    private final EarthCommandRouter router;

    public PaperEarthCommand(EarthCommandRouter router) {
        this.router = router;
    }

    @Override
    public void execute(CommandSourceStack commandSourceStack, String[] args) {
        CommandSender sender = commandSourceStack.getSender();
        CommandResult result = router.execute(sender, Arrays.asList(args));
        result.sendTo(sender);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack commandSourceStack, String[] args) {
        return router.suggest(commandSourceStack.getSender(), Arrays.asList(args));
    }
}
