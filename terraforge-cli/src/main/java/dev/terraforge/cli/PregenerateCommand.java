package dev.terraforge.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Plans and reports pregeneration work.
 *
 * <p>The CLI computes and validates the chunk set; the actual world writing is driven in-server by
 * {@code /earth pregenerate}, because only the server may write region files safely.
 */
@Command(name = "pregenerate", description = "Plan chunk pregeneration for a world or region.")
public final class PregenerateCommand implements Callable<Integer> {

    @Option(names = {"-w", "--world"}, defaultValue = "earth", description = "World name.")
    String world;

    @Option(names = {"-r", "--radius"}, required = true, description = "Radius in blocks around the origin.")
    int radius;

    @Override
    public Integer call() {
        return NotYetImplemented.report("pregenerate", "Phase 11 (pregeneration)");
    }
}
