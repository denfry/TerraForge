package dev.terraforge.cli.setup;

import dev.terraforge.core.config.TerraForgeConfig.TerrainSection;
import dev.terraforge.core.coord.GeoBounds;

/**
 * The vertical settings a region needs, and the world height they imply.
 *
 * <p>These are one decision, not two. {@code meters-per-block} says how dramatic the relief looks;
 * how many blocks the world needs follows from it and from how much of Earth's range the region
 * actually contains. Choosing them separately is how a world ends up valid, silent and flat.
 *
 * <p>The arithmetic that drives all of it: Minecraft's absolute ceiling is 4,064 blocks of world
 * height, and Earth's relief spans 19,784 m from the Mariana Trench to Everest. One metre per block
 * is therefore impossible at any setting -- Everest alone is more than twice the tallest world that
 * can exist. What remains is choosing how much to compress, and the compression is not a defect: at
 * true proportions (1,000 m per block, matching the horizontal scale) Everest is nine blocks, because
 * Earth's relief really is negligible against its size.
 */
public record VerticalProfile(int seaLevel, int minY, int maxY, double metersPerBlock) {

    private static final double EVEREST_METRES = 8849.0;
    private static final double MARIANA_METRES = 10935.0;

    /** Minecraft's own limits on a dimension type: {@code min_y >= -2032}, {@code min_y + height <= 2032}. */
    public static final int WORLD_FLOOR_LIMIT = -2032;
    public static final int WORLD_CEILING_LIMIT = 2032;

    /**
     * The planet profile: every mountain and every ocean floor at 20 m per block.
     *
     * <p>Everest lands 442 blocks above sea level and the abyssal plain 185 below, in a world of
     * 1,024 blocks -- 64 chunk sections against vanilla's 24. The Mariana Trench alone needs 547
     * blocks and is compressed into the last few; it stays the deepest place in the world, which is
     * the property that matters, and paying another 512 blocks of world height for one trench is
     * not a trade worth making.
     */
    public static VerticalProfile planet() {
        return new VerticalProfile(0, -512, 512, 20.0);
    }

    /** What TerraForge has always defaulted to: dramatic, and only honest for a regional world. */
    public static VerticalProfile regional() {
        return new VerticalProfile(63, -64, 320, 1.0);
    }

    /**
     * The profile suited to {@code bounds}.
     *
     * <p>A planet-wide box gets the planet profile; anything smaller keeps the regional default,
     * because a region a few hundred kilometres across rarely contains anything that needs it and
     * the vanilla world height costs a quarter of the memory.
     */
    public static VerticalProfile forBounds(GeoBounds bounds) {
        boolean planetary = bounds.latitudeSpan() >= 120.0 && bounds.longitudeSpan() >= 240.0;
        return planetary ? planet() : regional();
    }

    public VerticalProfile {
        if (minY >= maxY) {
            throw new IllegalArgumentException("min-y must be below max-y");
        }
        if (seaLevel <= minY || seaLevel >= maxY) {
            throw new IllegalArgumentException("sea-level must lie strictly between min-y and max-y");
        }
        if (!(metersPerBlock > 0.0) || !Double.isFinite(metersPerBlock)) {
            throw new IllegalArgumentException("meters-per-block must be greater than 0");
        }
        if (minY < WORLD_FLOOR_LIMIT || maxY > WORLD_CEILING_LIMIT) {
            throw new IllegalArgumentException("Minecraft allows world heights from "
                    + WORLD_FLOOR_LIMIT + " to " + WORLD_CEILING_LIMIT + "; asked for " + minY + ".." + maxY);
        }
        // (maxY - minY), not height(): in a compact constructor the fields are still unassigned,
        // so calling the accessor would measure a world of zero blocks and always pass.
        if ((maxY - minY) % 16 != 0) {
            throw new IllegalArgumentException(
                    "World height must be a multiple of 16: " + (maxY - minY));
        }
    }

    public int height() {
        return maxY - minY;
    }

    /** Chunk sections, the unit server memory and tick cost actually scale with. */
    public int chunkSections() {
        return height() / 16;
    }

    /** True when this profile needs a dimension type of its own rather than vanilla's. */
    public boolean needsDatapack() {
        return minY != -64 || maxY != 320;
    }

    public TerrainSection applyTo(TerrainSection defaults) {
        return new TerrainSection(seaLevel, minY, maxY, defaults.verticalExaggeration(),
                metersPerBlock, defaults.fallbackElevation(), defaults.bedrockThickness());
    }

    /** Blocks Everest occupies above sea level under this profile. */
    public int everestBlocks() {
        return (int) Math.round(EVEREST_METRES / metersPerBlock);
    }

    /** Blocks the Mariana Trench would need below sea level; more than the world has is compressed. */
    public int marianaBlocks() {
        return (int) Math.round(MARIANA_METRES / metersPerBlock);
    }

    /** One line for the operator, in the units the decision is actually made in. */
    public String describe() {
        int above = maxY - seaLevel;
        int below = seaLevel - minY;
        return String.format(java.util.Locale.ROOT,
                "%.0f m/block, world %d..%d (%d sections); Everest %d blocks of %d available up, "
                        + "Mariana %d of %d down",
                metersPerBlock, minY, maxY, chunkSections(),
                everestBlocks(), above, marianaBlocks(), below);
    }
}
