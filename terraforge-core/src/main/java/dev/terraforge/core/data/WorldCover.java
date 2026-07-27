package dev.terraforge.core.data;

import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;

/** ESA WorldCover v200 class-code mapping used by offline raster preparation. */
public final class WorldCover {

    private WorldCover() {
    }

    /** Converts an ESA WorldCover source value to TerraForge's stable biome vocabulary. */
    public static LandcoverClass fromCode(int code) {
        return switch (code) {
            case 10 -> LandcoverClass.TREE_COVER;
            case 20 -> LandcoverClass.SHRUBLAND;
            case 30 -> LandcoverClass.GRASSLAND;
            case 40 -> LandcoverClass.CROPLAND;
            case 50 -> LandcoverClass.BUILT_UP;
            case 60 -> LandcoverClass.BARE_SPARSE;
            case 70 -> LandcoverClass.SNOW_ICE;
            case 80 -> LandcoverClass.PERMANENT_WATER;
            case 90 -> LandcoverClass.HERBACEOUS_WETLAND;
            case 95 -> LandcoverClass.MANGROVES;
            case 100 -> LandcoverClass.MOSS_LICHEN;
            default -> LandcoverClass.UNKNOWN;
        };
    }
}
