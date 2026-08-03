package dev.terraforge.plugin.pregen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PregenerationCheckpointStoreTest {
    private static final String HASH = "a".repeat(64);

    @TempDir
    java.nio.file.Path directory;

    @Test
    void restartRestoresActiveWorkAsManuallyPaused() throws Exception {
        var store = new PregenerationCheckpointStore(directory);
        store.save(checkpoint(PregenerationState.RUNNING));

        var loaded = store.load().orElseThrow();

        assertThat(loaded.state()).isEqualTo(PregenerationState.PAUSED);
        assertThat(loaded.pauseReason()).isEqualTo("restart-required-manual-resume");
        assertThat(loaded.cursorOrdinal()).isEqualTo(7);
    }

    @Test
    void rejectsUnknownJsonFields() throws Exception {
        Files.writeString(directory.resolve("pregeneration.json"), "{}\n");

        assertThatThrownBy(() -> new PregenerationCheckpointStore(directory).load())
                .isInstanceOf(java.io.IOException.class);
    }

    private static PregenerationCheckpoint checkpoint(PregenerationState state) {
        return new PregenerationCheckpoint(PregenerationCheckpoint.SCHEMA_VERSION,
                PregenerationSpec.around(0, 0, 64), 7, 4, 2, 1, state, "testing",
                HASH, HASH, Instant.parse("2026-08-03T00:00:00Z").toEpochMilli());
    }
}
