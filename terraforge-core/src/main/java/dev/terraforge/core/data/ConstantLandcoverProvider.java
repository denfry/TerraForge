package dev.terraforge.core.data;

/**
 * Reports one land cover class everywhere.
 *
 * <p>Used until land cover data is imported (Phase 6): with {@link LandcoverClass#UNKNOWN} the
 * biome resolver falls back to latitude and elevation, which is coarse but never invents a desert
 * in Denmark. Also the simplest way to pin land cover in a test.
 */
public final class ConstantLandcoverProvider implements LandcoverProvider {

    private final LandcoverClass landcover;

    public ConstantLandcoverProvider(LandcoverClass landcover) {
        this.landcover = landcover;
    }

    /** The default until real land cover is prepared. */
    public static ConstantLandcoverProvider unknown() {
        return new ConstantLandcoverProvider(LandcoverClass.UNKNOWN);
    }

    @Override
    public LandcoverClass landcoverAt(double latitude, double longitude) {
        return landcover;
    }
}
