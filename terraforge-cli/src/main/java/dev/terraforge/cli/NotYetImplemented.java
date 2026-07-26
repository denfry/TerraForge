package dev.terraforge.cli;

/**
 * Marks a CLI command whose contract is fixed but whose implementation lands in a later phase.
 *
 * <p>Explicit and loud on purpose: a command that silently does nothing would leave an admin
 * believing their data was prepared.
 */
final class NotYetImplemented {

    private NotYetImplemented() {
    }

    /** Prints the phase this command is scheduled for and returns a non-zero exit code. */
    static int report(String command, String phase) {
        System.err.println("terraforge " + command + " is not implemented in this build.");
        System.err.println("Scheduled for " + phase + " -- see docs/pregeneration.md and the roadmap in README.md.");
        System.err.println("No data was written.");
        return 70; // EX_SOFTWARE
    }
}
