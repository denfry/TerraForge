package dev.terraforge.plugin.pregen;

/** Durable pregeneration lifecycle; terminal states cannot be resumed. */
public enum PregenerationState {
    PAUSED,
    RUNNING,
    AUTO_PAUSED,
    COMPLETED,
    CANCELLED;

    public boolean mayTransitionTo(PregenerationState next) {
        return switch (this) {
            case PAUSED -> next == RUNNING || next == CANCELLED;
            case RUNNING -> next == PAUSED || next == AUTO_PAUSED
                    || next == COMPLETED || next == CANCELLED;
            case AUTO_PAUSED -> next == RUNNING || next == PAUSED || next == CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
    }
}
