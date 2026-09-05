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
            case ALPINE -> new Layers(Material.SNOW_BLOCK, Material.STONE);
            case GLACIER -> new Layers(Material.SNOW_BLOCK, Material.SNOW_BLOCK);

            case MOUNTAIN_FOREST -> new Layers(Material.GRASS_BLOCK, Material.DIRT);
            case MOUNTAIN_MEADOW -> new Layers(Material.GRASS_BLOCK, Material.DIRT);
            case BARE_ROCK -> new Layers(Material.STONE, Material.STONE);
        };
    }

    /**
     * The sea bed: gravel 25 %, sand 35 %, clay 15 %, bone blocks 20 %, obsidian 5 %.
     *
     * <p>Chosen per five-block patch rather than per block, so the floor reads as sand banks, clay
     * beds and bone fields rather than static; a fifth of the columns draw on their own so the
     * patches have ragged edges. Both draws use the same weights, so the shares hold overall.
     */
    public static Material seabed(int x, int z) {
        double draw = CellNoise.at(x, z, SEABED_JITTER_SALT) < SEABED_JITTER
                ? CellNoise.at(x, z, SEABED_SALT)
                : CellNoise.cell(x, z, SEABED_PATCH, SEABED_SALT);
        if (draw < 0.35) {
            return Material.SAND;
        }
        if (draw < 0.60) {
            return Material.GRAVEL;
        }
        if (draw < 0.80) {
            return Material.BONE_BLOCK;
        }
        if (draw < 0.95) {
            return Material.CLAY;
        }
        return Material.OBSIDIAN;
    }

    /** The ice sheet: snow, with packed-ice patches on a tenth of it. */
    static Layers iceSheet(int x, int z) {
        boolean ice = CellNoise.cell(x, z, ICE_PATCH, ICE_SALT) < ICE_SHEET_ICE_SHARE;
        return ice ? new Layers(Material.PACKED_ICE, Material.PACKED_ICE)
                : new Layers(Material.SNOW_BLOCK, Material.SNOW_BLOCK);
    }
}
