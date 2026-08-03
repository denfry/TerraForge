package dev.terraforge.plugin.world;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Edits only the explicitly supplied Paper chunk settings for the managed earth world. */
public final class PaperWorldSettingsEditor {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public PlannedEdit plan(Path paperWorldYaml, ChunkSettings settings) throws IOException {
        byte[] original = Files.exists(paperWorldYaml) ? Files.readAllBytes(paperWorldYaml) : new byte[0];
        Map<String, Object> root = original.length == 0 ? new LinkedHashMap<>()
                : yaml.readValue(original, new TypeReference<LinkedHashMap<String, Object>>() {});
        if (root == null) root = new LinkedHashMap<>();
        Map<String, Object> chunks = map(root.get("chunks"));
        chunks.put("auto-save-interval", settings.autoSaveInterval());
        chunks.put("max-auto-save-chunks-per-tick", settings.maxAutoSaveChunksPerTick());
        chunks.put("delay-chunk-unloads-by", settings.delayChunkUnloadsBy());
        root.put("chunks", chunks);
        return new PlannedEdit(paperWorldYaml, original, yaml.writeValueAsBytes(root));
    }

    public record ChunkSettings(int autoSaveInterval, int maxAutoSaveChunksPerTick, String delayChunkUnloadsBy) {
        public ChunkSettings {
            if (autoSaveInterval < 1 || maxAutoSaveChunksPerTick < 1 || delayChunkUnloadsBy == null || delayChunkUnloadsBy.isBlank())
                throw new IllegalArgumentException("paper chunk settings must be positive");
        }
    }

    private static Map<String, Object> map(Object value) throws IOException {
        if (value == null) return new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> raw)) throw new IOException("paper-world.yml chunks must be a mapping");
        Map<String, Object> copy = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IOException("paper-world.yml keys must be strings");
            copy.put(key, entry.getValue());
        }
        return copy;
    }
}
