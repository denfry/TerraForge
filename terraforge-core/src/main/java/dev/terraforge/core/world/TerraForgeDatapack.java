package dev.terraforge.core.world;

import dev.terraforge.core.config.VerticalProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Renders the strictly limited datapack needed to override overworld height. */
public final class TerraForgeDatapack {
    public static final int PACK_FORMAT = 81;
    private TerraForgeDatapack() {}

    public static RenderedPack render(VerticalProfile profile) {
        if (profile == null) throw new IllegalArgumentException("vertical profile must be present");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("pack.mcmeta", ("{\n  \"pack\": {\n    \"pack_format\": " + PACK_FORMAT + ",\n    \"description\": \"TerraForge world height: " + profile.minY() + ".." + profile.maxY() + "\"\n  }\n}\n").getBytes(StandardCharsets.UTF_8));
        files.put("data/minecraft/dimension_type/overworld.json", ("{\n  \"ultrawarm\": false,\n  \"natural\": true,\n  \"piglin_safe\": false,\n  \"respawn_anchor_works\": false,\n  \"bed_works\": true,\n  \"has_raids\": true,\n  \"has_skylight\": true,\n  \"has_ceiling\": false,\n  \"coordinate_scale\": 1.0,\n  \"ambient_light\": 0.0,\n  \"logical_height\": " + profile.height() + ",\n  \"effects\": \"minecraft:overworld\",\n  \"infiniburn\": \"#minecraft:infiniburn_overworld\",\n  \"min_y\": " + profile.minY() + ",\n  \"height\": " + profile.height() + ",\n  \"monster_spawn_block_light_limit\": 0,\n  \"monster_spawn_light_level\": {\n    \"type\": \"minecraft:uniform\",\n    \"max_inclusive\": 7,\n    \"min_inclusive\": 0\n  }\n}\n").getBytes(StandardCharsets.UTF_8));
        return new RenderedPack(files, fingerprint(files));
    }

    private static String fingerprint(Map<String, byte[]> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (var entry : new TreeMap<>(files).entrySet()) { digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); digest.update(entry.getValue()); digest.update((byte) 0); }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    public record RenderedPack(Map<String, byte[]> files, String fingerprint) {
        public RenderedPack { files = Map.copyOf(files); }
    }
}
