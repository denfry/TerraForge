package dev.terraforge.generator.surface;

import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.generator.noise.CellNoise;
import org.bukkit.Material;

/**
 * Which blocks a climate biome is made of.
 *
 * <p>Three layers, which is all a real-world surface needs at TerraForge's resolution: the block
 * you walk on, the few blocks under it, and stone below that. Ore and cave generation is left to
 * vanilla, which already does it well and does it underground where geography no longer applies.
 *
 * <p>Two surfaces are not one block but a mix, chosen per column from a seedless hash of its
 * coordinates so every server draws the same sea bed: the ocean floor, and the polar ice sheet.
 */
public final class SurfacePalette {

    /** Blocks of {@code filler} placed between the top block and the stone below. */
    public static final int FILLER_DEPTH = 3;

    /** Share of ice-sheet columns that are ice rather than snow. */
    public static final double ICE_SHEET_ICE_SHARE = 0.10;

    private static final long SEABED_SALT = 0x5EABED0000000001L;
    private static final long SEABED_JITTER_SALT = 0x5EABED0000000002L;
    private static final long ICE_SALT = 0x1CE5A1700000003L;
    private static final long MOUNTAIN_SALT = 0x4D4F554E54000004L;
    private static final int MOUNTAIN_PATCH = 4;
    private static final int SEABED_PATCH = 5;
    private static final int ICE_PATCH = 4;
    /** Share of sea-bed columns that ignore their patch and draw on their own, so patches have ragged edges. */
    private static final double SEABED_JITTER = 0.2;

    private SurfacePalette() {
    }

    /**
     * @param top    the exposed surface block
     * @param filler the layer directly beneath it
     */
    public record Layers(Material top, Material filler) {
    }

    /**
     * The layers for one column. Equal to {@link #forBiome} for every biome whose surface is a
     * single block; the sea bed and the ice sheet vary with the column.
     */
    public static Layers forColumn(ClimateBiome biome, int x, int z) {
        return switch (biome) {
            case OCEAN, DEEP_OCEAN, WARM_OCEAN, FROZEN_OCEAN -> {
                Material bed = seabed(x, z);
                yield new Layers(bed, bed);
            }
            case GLACIER -> iceSheet(x, z);
            case ALPINE -> alpine(x, z);
            case BARE_ROCK -> bareRock(x, z);
            default -> forBiome(biome);
        };
    }

    public static Layers forBiome(ClimateBiome biome) {
        return switch (biome) {
            case OCEAN, DEEP_OCEAN, WARM_OCEAN, FROZEN_OCEAN -> new Layers(Material.GRAVEL, Material.GRAVEL);
            case LAKE, RIVER -> new Layers(Material.SAND, Material.SAND);
            case BEACH -> new Layers(Material.SAND, Material.SAND);
            case STONY_SHORE -> new Layers(Material.STONE, Material.STONE);

            case DESERT -> new Layers(Material.SAND, Material.SANDSTONE);
            case SEMI_DESERT -> new Layers(Material.TERRACOTTA, Material.TERRACOTTA);
            case SAVANNA, GRASSLAND, SHRUBLAND -> new Layers(Material.GRASS_BLOCK, Material.DIRT);
            case TEMPERATE_FOREST, TEMPERATE_RAINFOREST -> new Layers(Material.GRASS_BLOCK, Material.DIRT);
            case BOREAL_FOREST -> new Layers(Material.PODZOL, Material.DIRT);
            case TROPICAL_RAINFOREST, TROPICAL_SEASONAL_FOREST -> new Layers(Material.GRASS_BLOCK, Material.DIRT);
            case WETLAND -> new Layers(Material.GRASS_BLOCK, Material.CLAY);
            case MANGROVE -> new Layers(Material.MUD, Material.MUD);

            case TUNDRA -> new Layers(Material.SNOW_BLOCK, Material.DIRT);
            case ALPINE -> new Layers(Material.GRASS_BLOCK, Material.DIRT);
            case GLACIER -> new Layers(Material.SNOW_BLOCK, Material.SNOW_BLOCK);

            case MOUNTAIN_FOREST -> new Layers(Material.GRASS_BLOCK, Material.DIRT);
            case MOUNTAIN_MEADOW -> new Layers(Material.GRASS_BLOCK, Material.DIRT);
            case BARE_ROCK -> new Layers(Material.STONE, Material.STONE);
        };
    }

    /**
     * The alpine belt above the treeline: grass with scree, rock and late snow -- grass 50 %, coarse
     * dirt 15 %, stone 15 %, gravel 12 %, snow 8 %. It used to be snow throughout, which drew the
     * treeline across every mountain as the edge of a white sheet.
     */
    static Layers alpine(int x, int z) {
        double draw = patch(x, z);
        if (draw < 0.50) {
            return new Layers(Material.GRASS_BLOCK, Material.DIRT);
        }
        if (draw < 0.65) {
            return new Layers(Material.COARSE_DIRT, Material.DIRT);
        }
        if (draw < 0.80) {
            return new Layers(Material.STONE, Material.STONE);
        }
        if (draw < 0.92) {
            return new Layers(Material.GRAVEL, Material.STONE);
        }
        return new Layers(Material.SNOW_BLOCK, Material.STONE);
    }

    /**
     * Bare rock: stone 55 %, andesite 20 %, gravel 15 %, snow 10 % in sheltered patches. No tuff: a 1.16
     * client through ViaBackwards draws it as cobblestone, which looks laid by hand.
     */
    static Layers bareRock(int x, int z) {
        double draw = patch(x, z);
        if (draw < 0.55) {
            return new Layers(Material.STONE, Material.STONE);
        }
        if (draw < 0.75) {
            return new Layers(Material.ANDESITE, Material.STONE);
        }
        if (draw < 0.90) {
            return new Layers(Material.GRAVEL, Material.STONE);
        }
        return new Layers(Material.SNOW_BLOCK, Material.STONE);
    }

    /** A draw per four-block patch, a fifth of the columns on their own so patches have ragged edges. */
    private static double patch(int x, int z) {
        return CellNoise.at(x, z, MOUNTAIN_SALT + 1) < SEABED_JITTER
                ? CellNoise.at(x, z, MOUNTAIN_SALT)
                : CellNoise.cell(x, z, MOUNTAIN_PATCH, MOUNTAIN_SALT);
    }

    /**
     * The sea bed: sand 45 %, gravel 30 %, clay 15 %, mud 10 %. Bone blocks and obsidian, a quarter
     * of the floor before, read as a white and black chequerboard under every shallow sea.
     *
     * <p>Chosen per five-block patch rather than per block, so the floor reads as sand banks, clay
     * beds and bone fields rather than static; a fifth of the columns draw on their own so the
     * patches have ragged edges. Both draws use the same weights, so the shares hold overall.
     */
    public static Material seabed(int x, int z) {
        double draw = CellNoise.at(x, z, SEABED_JITTER_SALT) < SEABED_JITTER
                ? CellNoise.at(x, z, SEABED_SALT)
                : CellNoise.cell(x, z, SEABED_PATCH, SEABED_SALT);
        if (draw < 0.45) {
            return Material.SAND;
        }
        if (draw < 0.75) {
            return Material.GRAVEL;
        }
        if (draw < 0.90) {
            return Material.CLAY;
        }
        return Material.MUD;
    }

    /** The ice sheet: snow, with packed-ice patches on a tenth of it. */
    static Layers iceSheet(int x, int z) {
        boolean ice = CellNoise.cell(x, z, ICE_PATCH, ICE_SALT) < ICE_SHEET_ICE_SHARE;
        return ice ? new Layers(Material.PACKED_ICE, Material.PACKED_ICE)
                : new Layers(Material.SNOW_BLOCK, Material.SNOW_BLOCK);
    }
}
