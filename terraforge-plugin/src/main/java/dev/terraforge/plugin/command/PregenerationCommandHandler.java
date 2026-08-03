package dev.terraforge.plugin.command;

import dev.terraforge.plugin.pregen.PregenerationCheckpoint;
import dev.terraforge.plugin.pregen.PregenerationController;
import dev.terraforge.plugin.pregen.PregenerationSpec;
import dev.terraforge.plugin.pregen.PregenerationState;
import dev.terraforge.plugin.pregen.ServerHealthSnapshot;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;

/**
 * Routes {@code /earth pregenerate start|full|pause|resume|status|cancel} to {@link
 * PregenerationController}.
 *
 * <p>The controller's own admin methods ({@code begin}/{@code pause}/{@code resume}/{@code cancel})
 * are deliberately permissive -- they no-op rather than throw when a transition is not legal, so
 * they stay race-safe under concurrent callers. Every rejection the brief requires (duplicate active
 * job, fingerprint mismatch, failed disk/DEM checks) is therefore enforced here, before the
 * controller is ever called, so the operator gets a specific reason instead of a silent no-op.
 */
public final class PregenerationCommandHandler implements EarthSubcommand {
    static final String PERMISSION = "terraforge.command.pregenerate";
    private static final List<String> VERBS = List.of("start", "full", "pause", "resume", "status", "cancel");
    private static final String UNAVAILABLE = "Pregeneration is unavailable: the managed Earth world "
            + "is not ready, or no pregeneration controller is active.";

    private final PregenerationCommandContext context;

    public PregenerationCommandHandler(PregenerationCommandContext context) {
        this.context = context;
    }

    @Override
    public CommandResult execute(CommandSender sender, List<String> args) {
        if (!sender.hasPermission(PERMISSION)) return CommandResult.denied();
        if (args.isEmpty()) return usage();
        List<String> rest = args.subList(1, args.size());
        return switch (args.get(0).toLowerCase(Locale.ROOT)) {
            case "start" -> start(rest);
            case "full" -> full(rest);
            case "pause" -> noArgs(rest, this::pause);
            case "resume" -> noArgs(rest, this::resume);
            case "status" -> noArgs(rest, this::status);
            case "cancel" -> noArgs(rest, this::cancel);
            default -> usage();
        };
    }

    @Override
    public List<String> suggest(CommandSender sender, List<String> args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.size() == 1) {
            String prefix = args.get(0).toLowerCase(Locale.ROOT);
            return VERBS.stream().filter(verb -> verb.startsWith(prefix)).toList();
        }
        if (args.size() == 2 && "full".equals(args.get(0).toLowerCase(Locale.ROOT))) {
            String prefix = args.get(1).toLowerCase(Locale.ROOT);
            return "confirm".startsWith(prefix) ? List.of("confirm") : List.of();
        }
        return List.of();
    }

    @Override
    public boolean isVisibleTo(CommandSender sender) {
        return sender.hasPermission(PERMISSION);
    }

    private CommandResult noArgs(List<String> rest, Supplier<CommandResult> action) {
        if (!rest.isEmpty()) return usage();
        return action.get();
    }

    private CommandResult start(List<String> args) {
        if (args.size() != 1 && args.size() != 3) return usage();
        int radius;
        try {
            radius = Integer.parseInt(args.get(0));
        } catch (NumberFormatException exception) {
            return CommandResult.error("radius-blocks must be a whole number.");
        }
        int centerX = 0;
        int centerZ = 0;
        if (args.size() == 3) {
            try {
                centerX = Integer.parseInt(args.get(1));
                centerZ = Integer.parseInt(args.get(2));
            } catch (NumberFormatException exception) {
                return CommandResult.error("center-x and center-z must be whole numbers.");
            }
        }
        PregenerationSpec spec;
        try {
            spec = PregenerationSpec.around(centerX, centerZ, radius);
        } catch (IllegalArgumentException exception) {
            return CommandResult.error("Cannot start pregeneration: " + exception.getMessage());
        }
        return beginJob(spec);
    }

    private CommandResult full(List<String> args) {
        if (args.size() != 1 || !"confirm".equals(args.get(0))) {
            return CommandResult.warning("Usage: /earth pregenerate full confirm -- pregenerates the "
                    + "entire configured region; the literal 'confirm' is required.");
        }
        return beginJob(context.fullRegionSpec());
    }

    private CommandResult beginJob(PregenerationSpec spec) {
        if (!context.managedWorldReady() || context.controller().isEmpty()) {
            return CommandResult.error(UNAVAILABLE);
        }
        PregenerationController controller = context.controller().orElseThrow();
        if (isActiveJob(controller)) {
            return CommandResult.error("A pregeneration job is already active; "
                    + "cancel it or wait for it to finish before starting another.");
        }
        controller.begin(spec, context.currentConfigFingerprint(), context.currentDataFingerprint());
        return CommandResult.success("Pregeneration job created for radius " + spec.radiusBlocks()
                + " around (" + spec.centerBlockX() + ", " + spec.centerBlockZ()
                + "); it starts paused -- run /earth pregenerate resume to begin.");
    }

    private CommandResult pause() {
        if (!context.managedWorldReady() || context.controller().isEmpty()) return CommandResult.error(UNAVAILABLE);
        context.controller().orElseThrow().pause("manual-pause");
        return CommandResult.success("Pregeneration paused.");
    }

    private CommandResult resume() {
        if (!context.managedWorldReady() || context.controller().isEmpty()) return CommandResult.error(UNAVAILABLE);
        PregenerationController controller = context.controller().orElseThrow();
        Optional<PregenerationCheckpoint> checkpointOpt = controller.status();
        if (checkpointOpt.isEmpty()) {
            return CommandResult.error("No pregeneration job exists yet; use /earth pregenerate start first.");
        }
        PregenerationCheckpoint checkpoint = checkpointOpt.get();
        if (!checkpoint.configFingerprint().equals(context.currentConfigFingerprint())
                || !checkpoint.dataFingerprint().equals(context.currentDataFingerprint())) {
            return CommandResult.error("Cannot resume: the checkpointed config/data fingerprint no longer "
                    + "matches the live server; cancel this job and start a new one.");
        }
        ServerHealthSnapshot health = context.currentHealth();
        if (!health.demCoverageAvailable()) {
            return CommandResult.error("Cannot resume: DEM coverage is currently unavailable.");
        }
        if (health.usableDiskGb() < context.reserveDiskGb()) {
            return CommandResult.error("Cannot resume: usable disk space is below the configured reserve.");
        }
        controller.resume();
        return CommandResult.success("Pregeneration resumed.");
    }

    private CommandResult cancel() {
        if (!context.managedWorldReady() || context.controller().isEmpty()) return CommandResult.error(UNAVAILABLE);
        context.controller().orElseThrow().cancel();
        return CommandResult.success("Pregeneration job cancelled.");
    }

    private CommandResult status() {
        if (!context.managedWorldReady() || context.controller().isEmpty()) return CommandResult.error(UNAVAILABLE);
        PregenerationController controller = context.controller().orElseThrow();
        CommandResult.Builder builder = CommandResult.builder();
        Optional<PregenerationCheckpoint> checkpointOpt = controller.status();
        if (checkpointOpt.isEmpty()) {
            builder.line(CommandResult.Level.INFO, "No pregeneration job has been created yet.");
        } else {
            PregenerationCheckpoint checkpoint = checkpointOpt.get();
            long ageSeconds = Math.max(0, (context.clock().millis() - checkpoint.updatedAtEpochMillis()) / 1000);
            String state = checkpoint.state()
                    + (checkpoint.state() == PregenerationState.AUTO_PAUSED ? " (" + checkpoint.pauseReason() + ")" : "");
            builder.line(CommandResult.Level.INFO, "State: " + state);
            builder.line(CommandResult.Level.INFO, "Progress: completed=" + checkpoint.completed()
                    + " skipped=" + checkpoint.skipped() + " failed=" + checkpoint.failed()
                    + " total=" + checkpoint.spec().totalChunks() + " in-flight=" + controller.inFlightCount());
            builder.line(CommandResult.Level.INFO, "Region: center=(" + checkpoint.spec().centerBlockX() + ", "
                    + checkpoint.spec().centerBlockZ() + ") radius=" + checkpoint.spec().radiusBlocks());
            builder.line(CommandResult.Level.INFO, "Checkpoint age: " + ageSeconds + "s");
        }
        ServerHealthSnapshot health = context.currentHealth();
        builder.line(CommandResult.Level.INFO, "Health: tps=" + fmt(health.tps()) + " mspt=" + fmt(health.mspt())
                + " usableDiskGb=" + health.usableDiskGb() + " reserveGb=" + context.reserveDiskGb()
                + " onlinePlayerPolicy=" + (context.pauseWhenPlayersOnline() ? "pause-when-online" : "always-allowed"));
        return builder.build();
    }

    private static boolean isActiveJob(PregenerationController controller) {
        return controller.status().map(PregenerationCheckpoint::state).map(PregenerationCommandHandler::isActive)
                .orElse(false);
    }

    private static boolean isActive(PregenerationState state) {
        return state == PregenerationState.RUNNING || state == PregenerationState.PAUSED
                || state == PregenerationState.AUTO_PAUSED;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private CommandResult usage() {
        return CommandResult.warning("Usage: /earth pregenerate <start <radius> [x z]|full confirm|"
                + "pause|resume|status|cancel>");
    }
}
