package dev.terraforge.plugin.pregen;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.terraforge.plugin.io.AtomicFileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

/** Strict atomic persistence at plugins/TerraForge/pregeneration.json. */
public final class PregenerationCheckpointStore {
    private final Path file;
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private final Clock clock;

    public PregenerationCheckpointStore(Path pluginRoot) {
        this(pluginRoot, Clock.systemUTC());
    }

    PregenerationCheckpointStore(Path pluginRoot, Clock clock) {
        Path normalizedRoot = pluginRoot.toAbsolutePath().normalize();
        this.file = normalizedRoot.resolve("pregeneration.json");
        this.clock = clock;
    }

    public Optional<PregenerationCheckpoint> load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            PregenerationCheckpoint checkpoint = json.readValue(
                    Files.readAllBytes(file), PregenerationCheckpoint.class);
            if (checkpoint.state() == PregenerationState.RUNNING
                    || checkpoint.state() == PregenerationState.AUTO_PAUSED) {
                checkpoint = checkpoint.withState(PregenerationState.PAUSED,
                        "restart-required-manual-resume", clock.millis());
                save(checkpoint);
            }
            return Optional.of(checkpoint);
        } catch (IllegalArgumentException exception) {
            throw new IOException("pregeneration checkpoint is invalid", exception);
        }
    }

    public void save(PregenerationCheckpoint checkpoint) throws IOException {
        try {
            AtomicFileWriter.write(file, json.writeValueAsBytes(checkpoint));
        } catch (IllegalArgumentException exception) {
            throw new IOException("pregeneration checkpoint is invalid", exception);
        }
    }

    public void delete() throws IOException {
        Files.deleteIfExists(file);
    }
}
