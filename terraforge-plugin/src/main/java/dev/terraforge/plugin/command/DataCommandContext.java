package dev.terraforge.plugin.command;

/**
 * Everything {@link DataCommandHandler} needs to report prepared-DEM inventory, kept narrow and
 * server-free so the handler is testable with a fake implementation.
 */
public interface DataCommandContext {
    /** Prepared tile count, read from {@code FileDemReader}'s in-memory catalogue -- never a disk scan. */
    int tileCount();

    /** Human-readable geographic coverage of the prepared tiles. */
    String coverageDescription();

    boolean hasBathymetry();

    /** Tiles a chunk asked for that were not prepared, tracked live by {@code DemElevationProvider}. */
    int missingRequestedTileCount();

    /** Last background-scanned corrupt/unreadable file count; instant to read, never scans on this thread. */
    int cachedCorruptFileCount();

    /** Kicks a background rescan of the DEM directory for the next call; must not block the caller. */
    void refreshCorruptionAsync();
}
