package dev.terraforge.plugin.command;

import java.util.List;
import org.bukkit.command.CommandSender;

/**
 * One feature family behind {@code /earth}, e.g. {@code world}. Implementations must not touch
 * {@link CommandSender#sendMessage} directly -- return a {@link CommandResult} instead, so the caller
 * (tests, or {@code EarthCommandRouter}) decides whether and how to deliver it.
 */
public interface EarthSubcommand {
    /** @param args the arguments after the family name, e.g. {@code ["plan"]} for {@code /earth world plan} */
    CommandResult execute(CommandSender sender, List<String> args);

    /** @param args the partial arguments after the family name, mirroring {@link #execute} */
    List<String> suggest(CommandSender sender, List<String> args);

    /** Whether the family name itself should be offered to {@code sender} at the {@code /earth <tab>} level. */
    default boolean isVisibleTo(CommandSender sender) { return true; }
}
