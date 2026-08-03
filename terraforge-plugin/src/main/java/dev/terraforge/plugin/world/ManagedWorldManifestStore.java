package dev.terraforge.plugin.world;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.terraforge.plugin.io.AtomicFileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.HashSet;

/** Strict JSON persistence for plugins/TerraForge/managed-world.json. */
public final class ManagedWorldManifestStore {
    private final Path file;
    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    public ManagedWorldManifestStore(Path pluginRoot) { this.file = pluginRoot.resolve("managed-world.json").normalize(); }
    public Optional<ManagedWorldManifest> load() throws IOException {
        if (!Files.isRegularFile(file)) return Optional.empty();
        ManagedWorldManifest manifest = json.readValue(Files.readAllBytes(file), ManagedWorldManifest.class);
        validate(manifest); return Optional.of(manifest);
    }
    public void save(ManagedWorldManifest manifest) throws IOException {
        validate(manifest);
        Optional<ManagedWorldManifest> current = load();
        if (current.isPresent() && current.get().equals(manifest)) return;
        ManagedWorldState from = current.map(ManagedWorldManifest::state).orElse(ManagedWorldState.ABSENT);
        if (!from.mayTransitionTo(manifest.state())) throw new IOException("illegal managed-world state transition");
        AtomicFileWriter.write(file, json.writeValueAsBytes(manifest));
    }
    private static void validate(ManagedWorldManifest m) throws IOException {
        if (m == null || m.schemaVersion() != ManagedWorldManifest.SCHEMA_VERSION || m.state() == null
                || m.state() == ManagedWorldState.ABSENT || !"earth".equals(m.worldName())
                || m.minY() >= m.maxY() || !fingerprint(m.configFingerprint())
                || !fingerprint(m.dataFingerprint()) || !fingerprint(m.datapackFingerprint())
                || m.ownedStagingFiles() == null || m.ownedStagingFiles().isEmpty()
                || m.reason() == null || m.reason().length() > 256) {
            throw new IOException("managed-world manifest is invalid");
        }
        HashSet<Path> owned = new HashSet<>();
        for (String value : m.ownedStagingFiles()) {
            try {
                Path path = Path.of(value).normalize();
                if (value.isBlank() || path.isAbsolute() || path.startsWith("..") || !owned.add(path)) {
                    throw new IOException("managed-world manifest has unsafe owned paths");
                }
            } catch (java.nio.file.InvalidPathException exception) {
                throw new IOException("managed-world manifest has unsafe owned paths", exception);
            }
        }
    }
    private static boolean fingerprint(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
