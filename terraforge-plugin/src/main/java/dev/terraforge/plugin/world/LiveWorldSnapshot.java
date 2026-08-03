package dev.terraforge.plugin.world;

/** Bukkit adapter supplies this narrow live-world view after the primary world has loaded. */
public record LiveWorldSnapshot(String name, boolean primary, boolean terraForgeGenerator, int minY, int maxY,
                                boolean earthHeightDatapackEnabled, String configFingerprint, String dataFingerprint,
                                String datapackFingerprint) {}
