package dev.terraforge.core.coord;

/**
 * A horizontal Minecraft position with sub-block precision.
 *
 * <p>Deliberately not a Bukkit {@code Location}: the core module must stay free of Paper types.
 */
public record MinecraftPos(double x, double z) {

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }

    public int chunkX() {
        return blockX() >> 4;
    }

    public int chunkZ() {
        return blockZ() >> 4;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "%.2f, %.2f", x, z);
    }
}
