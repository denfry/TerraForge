package dev.terraforge.plugin.pregen;

/** Atomic durable state for one deterministic pregeneration job. */
public record PregenerationCheckpoint(int schemaVersion, PregenerationSpec spec, long cursorOrdinal,
                                      long completed, long skipped, long failed,
                                      PregenerationState state, String pauseReason,
                                      String configFingerprint, String dataFingerprint,
                                      long updatedAtEpochMillis) {
    public static final int SCHEMA_VERSION = 1;

    public PregenerationCheckpoint {
        if (schemaVersion != SCHEMA_VERSION || spec == null || cursorOrdinal < 0
                || completed < 0 || skipped < 0 || failed < 0
                || Math.addExact(Math.addExact(completed, skipped), failed) > cursorOrdinal
                || state == null || pauseReason == null || pauseReason.length() > 256
                || !fingerprint(configFingerprint) || !fingerprint(dataFingerprint)
                || updatedAtEpochMillis < 0) {
            throw new IllegalArgumentException("invalid pregeneration checkpoint");
        }
    }

    public PregenerationCheckpoint withState(PregenerationState next, String reason, long nowEpochMillis) {
        if (!state.mayTransitionTo(next)) {
            throw new IllegalStateException("illegal pregeneration transition");
        }
        return new PregenerationCheckpoint(schemaVersion, spec, cursorOrdinal, completed, skipped,
                failed, next, reason, configFingerprint, dataFingerprint, nowEpochMillis);
    }

    private static boolean fingerprint(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
