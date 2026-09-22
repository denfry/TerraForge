package dev.terraforge.generator.underground;

import java.util.ArrayList;
import java.util.List;

/**
 * Vanilla 1.16's ores and stone pockets, moved into this world's vertical frame.
 *
 * <p>Vanilla puts its bands at fixed Y values that assume sea level 63; here every band keeps its
 * depth below the sea instead, so in a world with {@code sea-level: 63, min-y: 0} the table is
 * exactly vanilla 1.16's, and in a deeper world it moves down with the sea. Rock vanilla never had --
 * below vanilla's y=0, or mountains above y=127 -- gets the density of the nearest vanilla layer
 * rather than nothing: diamonds and redstone keep appearing all the way down, coal and some iron all
 * the way up.
 *
 * <p>Counts are per chunk and match vanilla wherever the band is vanilla's own; bands stretched over a
 * taller range scale their count with it, so density per block stays the same.
 */
final class OreTable {

    /** Vanilla's sea level, the anchor every vanilla band height is measured from. */
    static final int VANILLA_SEA_LEVEL = 63;

    /** Real ground this high above the sea counts as mountains, where vanilla 1.16 hid its emerald. */
    static final double EMERALD_MIN_ELEVATION_METRES = 1000.0;

    private OreTable() {
    }

    /** Dirt, gravel, granite, diorite and andesite pockets. */
    static List<OreBand> stoneVariety(int floorY, int seaLevel, int topY) {
        Frame frame = new Frame(floorY, seaLevel, topY);
        List<OreBand> bands = new ArrayList<>();
        frame.wholeHeight(bands, "dirt", Mineral.DIRT, 33, 10);
        frame.wholeHeight(bands, "gravel", Mineral.GRAVEL, 33, 8);
        frame.vanilla(bands, "granite", Mineral.GRANITE, 33, 10, 0, 79, false, true);
        frame.vanilla(bands, "diorite", Mineral.DIORITE, 33, 10, 0, 79, false, true);
        frame.vanilla(bands, "andesite", Mineral.ANDESITE, 33, 10, 0, 79, false, true);
        // 1.18 keeps tuff to the deep layer; here the bottom of vanilla's old world, and below it.
        frame.vanilla(bands, "tuff", Mineral.TUFF, 33, 4, 0, 16, false, true);
        // Not an overworld block in vanilla, but dark basaltic pockets near the bedrock read as the
        // deep crust -- and give the deep layer a second colour next to tuff.
        frame.vanilla(bands, "blackstone", Mineral.BLACKSTONE, 33, 2, 0, 12, false, true);
        return List.copyOf(bands);
    }

    /** Coal, iron, copper, gold, redstone, lapis, diamond and emerald veins. */
    static List<OreBand> ores(int floorY, int seaLevel, int topY) {
        Frame frame = new Frame(floorY, seaLevel, topY);
        List<OreBand> bands = new ArrayList<>();
        frame.vanilla(bands, "coal", Mineral.COAL, 17, 20, 0, 127, false, false);
        frame.above(bands, "coal_high", Mineral.COAL, 17, 10, 127);
        frame.vanilla(bands, "iron", Mineral.IRON, 9, 20, 0, 63, false, true);
        frame.above(bands, "iron_high", Mineral.IRON, 9, 6, 127);
        // Copper is 1.17's: six veins of ten between vanilla's y=0 and y=96.
        frame.vanilla(bands, "copper", Mineral.COPPER, 10, 6, 0, 96, false, false);
        frame.vanilla(bands, "gold", Mineral.GOLD, 9, 2, 0, 31, false, true);
        frame.vanilla(bands, "redstone", Mineral.REDSTONE, 8, 8, 0, 15, false, true);
        frame.vanilla(bands, "diamond", Mineral.DIAMOND, 8, 1, 0, 15, false, true);
        frame.vanilla(bands, "lapis", Mineral.LAPIS, 7, 1, 0, 30, true, true);
        // Vanilla 1.16: three to eight single blocks between y=4 and y=31, in mountains only.
        OreBand emerald = frame.clip("emerald", Mineral.EMERALD, 1, 5.5, 4, 31, false,
                EMERALD_MIN_ELEVATION_METRES);
        if (emerald != null) {
            bands.add(emerald);
        }
        return List.copyOf(bands);
    }

    /**
     * @param floorY   lowest Y above the bedrock
     * @param seaLevel this world's sea level
     * @param topY     one above the highest buildable Y
     */
    private record Frame(int floorY, int seaLevel, int topY) {

        int shift() {
            return seaLevel - VANILLA_SEA_LEVEL;
        }

        /** A vanilla band moved to this frame; {@code deep} continues it, at its density, to the floor. */
        void vanilla(List<OreBand> bands, String name, Mineral mineral, int size, double count,
                     int vanillaMin, int vanillaMax, boolean triangular, boolean deep) {
            OreBand band = clip(name, mineral, size, count, vanillaMin, vanillaMax, triangular, Double.NaN);
            if (band != null) {
                bands.add(band);
            }
            int bottom = vanillaMin + shift();
            if (deep && floorY < bottom) {
                int deepMax = Math.min(bottom - 1, topY - 1);
                double perBlock = count / (vanillaMax - vanillaMin + 1);
                bands.add(new OreBand(name + "_deep", mineral, size, perBlock * (deepMax - floorY + 1),
                        floorY, deepMax, false, Double.NaN));
            }
        }

        /** Rock above vanilla's {@code vanillaTop}, which vanilla 1.16 never had, at {@code countPer128}. */
        void above(List<OreBand> bands, String name, Mineral mineral, int size, double countPer128,
                   int vanillaTop) {
            int from = Math.max(floorY, vanillaTop + shift() + 1);
            int to = topY - 1;
            if (from <= to) {
                bands.add(new OreBand(name, mineral, size, countPer128 * (to - from + 1) / 128.0,
                        from, to, false, Double.NaN));
            }
        }

        /** Pockets that vanilla spreads over its whole 256-block world, at the same density here. */
        void wholeHeight(List<OreBand> bands, String name, Mineral mineral, int size, double countPer256) {
            int to = topY - 1;
            if (floorY <= to) {
                bands.add(new OreBand(name, mineral, size, countPer256 * (to - floorY + 1) / 256.0,
                        floorY, to, false, Double.NaN));
            }
        }

        /** The vanilla range in this frame, cut to the rock that exists; {@code null} when none does. */
        OreBand clip(String name, Mineral mineral, int size, double count, int vanillaMin, int vanillaMax,
                     boolean triangular, double minElevationMetres) {
            int min = Math.max(floorY, vanillaMin + shift());
            int max = Math.min(topY - 1, vanillaMax + shift());
            if (min > max) {
                return null;
            }
            return new OreBand(name, mineral, size, count, min, max, triangular, minElevationMetres);
        }
    }
}
