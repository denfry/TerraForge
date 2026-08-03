package dev.terraforge.plugin.world;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Merges TerraForge's generator entry without selecting or modifying a spawn world. */
public final class BukkitWorldsEditor {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public PlannedEdit plan(Path bukkitYaml) throws IOException {
        byte[] original = Files.exists(bukkitYaml) ? Files.readAllBytes(bukkitYaml) : new byte[0];
        Map<String, Object> root = original.length == 0 ? new LinkedHashMap<>()
                : yaml.readValue(original, new TypeReference<LinkedHashMap<String, Object>>() {});
        if (root == null) root = new LinkedHashMap<>();
        Map<String, Object> worlds = map(root.get("worlds"));
        Map<String, Object> earth = map(worlds.get("earth"));
        earth.put("generator", "TerraForge");
        worlds.put("earth", earth);
        root.put("worlds", worlds);
        return new PlannedEdit(bukkitYaml, original, yaml.writeValueAsBytes(root));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) throws IOException {
        if (value == null) return new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> raw)) throw new IOException("bukkit.yml section must be a mapping");
        Map<String, Object> copy = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IOException("bukkit.yml keys must be strings");
            copy.put(key, entry.getValue());
        }
        return copy;
    }
}
