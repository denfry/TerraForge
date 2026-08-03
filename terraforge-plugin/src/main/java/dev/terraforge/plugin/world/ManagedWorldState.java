package dev.terraforge.plugin.world;

/** Persisted managed-world lifecycle. Invalid is terminal and requires operator diagnosis. */
public enum ManagedWorldState {
    ABSENT, PENDING_RESTART, CREATING, READY, INVALID;

    public boolean mayTransitionTo(ManagedWorldState next) {
        return switch (this) {
            case ABSENT -> next == PENDING_RESTART;
            case PENDING_RESTART -> next == CREATING || next == INVALID;
            case CREATING -> next == READY || next == INVALID;
            case READY -> next == INVALID;
            case INVALID -> false;
        };
    }
}
