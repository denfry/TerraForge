package dev.terraforge.core.data;

/**
 * Real land cover at a geographic point, used to resolve biomes and surface materials.
 *
 * <p>The vocabulary follows ESA WorldCover, the dataset recommended in DATA_SOURCES.md, so
 * preparation is a class remap rather than a reinterpretation.
 */
public interface LandcoverProvider {

    LandcoverClass landcoverAt(double latitude, double longitude);

    /**
     * Land cover classes.
     *
     * <p>{@link #BUILT_UP} exists because the source data contains it -- it is <em>read</em>, never
     * built. The generator maps it to the surrounding natural cover; TerraForge places no man-made
     * structure under any circumstance.
     */
    enum LandcoverClass {
        UNKNOWN,
        TREE_COVER,
        SHRUBLAND,
        GRASSLAND,
        CROPLAND,
        BUILT_UP,
        BARE_SPARSE,
        SNOW_ICE,
        PERMANENT_WATER,
        HERBACEOUS_WETLAND,
        MANGROVES,
        MOSS_LICHEN;

        public boolean isVegetated() {
            return this == TREE_COVER || this == SHRUBLAND || this == GRASSLAND
                    || this == CROPLAND || this == HERBACEOUS_WETLAND || this == MANGROVES;
        }
    }
}
