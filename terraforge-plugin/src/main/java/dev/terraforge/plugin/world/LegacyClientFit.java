package dev.terraforge.plugin.world;

/**
 * Whether players on pre-1.17 clients can see this world whole.
 *
 * <p>A 1.16 client knows one world height, y 0..255. ViaBackwards lets such a client join a newer
 * server, but it can only drop what lies outside that range: below y=0 the client sees void, so
 * ocean floors, ores and caves down there do not exist for it, and a player standing there falls
 * through nothing. The server never notices -- it holds the blocks -- so the only place to say so is
 * the startup log.
 */
public final class LegacyClientFit {

    /** The world a 1.16 client can show: y 0 up to, not including, 256. */
    public static final int LEGACY_MIN_Y = 0;
    public static final int LEGACY_MAX_Y = 256;

    private LegacyClientFit() {
    }

    /**
     * The warning to log, or {@code null} when old clients are not admitted or see everything.
     *
     * @param viaBackwards whether ViaBackwards is installed, i.e. whether old clients can join at all
     */
    public static String warning(int minY, int maxY, boolean viaBackwards) {
        if (!viaBackwards || (minY >= LEGACY_MIN_Y && maxY <= LEGACY_MAX_Y)) {
            return null;
        }
        return String.format(java.util.Locale.ROOT,
                "ViaBackwards is installed, but this world spans y %d..%d and a 1.16 client can only show "
                        + "y %d..%d. Everything outside that range -- sea beds, ores, caves -- is void to "
                        + "those players. Set terrain.min-y: %d and terrain.max-y: %d (re-staging the world) "
                        + "if old clients must play here; see docs/vertical-scale.md.",
                minY, maxY - 1, LEGACY_MIN_Y, LEGACY_MAX_Y - 1, LEGACY_MIN_Y, LEGACY_MAX_Y);
    }
}
