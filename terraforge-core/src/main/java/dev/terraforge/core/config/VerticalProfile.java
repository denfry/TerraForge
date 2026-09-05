package dev.terraforge.core.config;

import dev.terraforge.core.coord.GeoBounds;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** The vertical settings a world needs, and the height they imply. */
public record VerticalProfile(int seaLevel, int minY, int maxY, double metersPerBlock) {
    private static final double EVEREST_METRES = 8849.0;
    private static final double MARIANA_METRES = 10935.0;
    public static final int WORLD_FLOOR_LIMIT = -2032;
    public static final int WORLD_CEILING_LIMIT = 2032;

    public static VerticalProfile from(TerraForgeConfig.TerrainSection terrain) {
        if (terrain == null) {
            throw new IllegalArgumentException("terrain configuration must be present");
        }
        return new VerticalProfile(terrain.seaLevel(), terrain.minY(), terrain.maxY(), terrain.metersPerBlock());
    }

    public static VerticalProfile planet() { return new VerticalProfile(0, -512, 512, 20.0); }
    public static VerticalProfile regional() { return new VerticalProfile(63, -64, 320, 1.0); }
    public static VerticalProfile forBounds(GeoBounds bounds) {
        return bounds.latitudeSpan() >= 120.0 && bounds.longitudeSpan() >= 240.0 ? planet() : regional();
    }

    public VerticalProfile {
        if (minY >= maxY) throw new IllegalArgumentException("min-y must be below max-y");
        if (seaLevel <= minY || seaLevel >= maxY) throw new IllegalArgumentException("sea-level must lie strictly between min-y and max-y");
        if (!(metersPerBlock > 0.0) || !Double.isFinite(metersPerBlock)) throw new IllegalArgumentException("meters-per-block must be greater than 0");
        if (minY < WORLD_FLOOR_LIMIT || maxY > WORLD_CEILING_LIMIT) throw new IllegalArgumentException("Minecraft allows world heights from " + WORLD_FLOOR_LIMIT + " to " + WORLD_CEILING_LIMIT + "; asked for " + minY + ".." + maxY);
        if ((maxY - minY) % 16 != 0) throw new IllegalArgumentException("World height must be a multiple of 16: " + (maxY - minY));
    }

    public int height() { return maxY - minY; }
    public int chunkSections() { return height() / 16; }
    public boolean needsDatapack() { return minY != -64 || maxY != 320; }
    public TerraForgeConfig.TerrainSection applyTo(TerraForgeConfig.TerrainSection defaults) {
        return new TerraForgeConfig.TerrainSection(seaLevel, minY, maxY, defaults.verticalExaggeration(), metersPerBlock, defaults.fallbackElevation(), defaults.bedrockThickness(), defaults.smoothing());
    }
    /**
     * SHA-256 over the exact values that determine a managed world's height, used as the config
     * fingerprint recorded into and verified against {@code ManagedWorldManifest}. Identical values
     * always yield the same fingerprint regardless of process or platform.
     */
    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = String.format(Locale.ROOT, "seaLevel=%d;minY=%d;maxY=%d;metersPerBlock=%s",
                    seaLevel, minY, maxY, Double.toString(metersPerBlock));
            digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public int everestBlocks() { return (int) Math.round(EVEREST_METRES / metersPerBlock); }
    public int marianaBlocks() { return (int) Math.round(MARIANA_METRES / metersPerBlock); }
    public String describe() {
        return String.format(java.util.Locale.ROOT, "%.0f m/block, world %d..%d (%d sections); Everest %d blocks of %d available up, Mariana %d of %d down", metersPerBlock, minY, maxY, chunkSections(), everestBlocks(), maxY - seaLevel, marianaBlocks(), seaLevel - minY);
    }
}
