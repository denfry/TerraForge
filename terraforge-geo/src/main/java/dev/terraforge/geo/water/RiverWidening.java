package dev.terraforge.geo.water;

import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.core.projection.Projection;

/**
 * Widens prepared rivers when the world is generated, without preparing the database again.
 *
 * <p>The importer draws a river {@code 7.2 * sqrt(Q)} metres wide and never narrower than one block.
 * At a kilometre per block that is one block for nearly every river on Earth -- the Rhône and the Po
 * included -- and a one-block river is a ditch: too narrow for a boat, and the crowns of the trees on
 * its banks close over it, so from above it shows as a dotted line. This pass buffers each river's
 * polygon so it is at least {@code minWidthBlocks} wide, and wider the more water it carries: half as
 * much again per tenfold discharge over {@value #REFERENCE_DISCHARGE} m³/s. A river already wider in
 * the database keeps its own width.
 *
 * <p>Widths are measured in blocks along a meridian. The equirectangular world draws a degree of
 * longitude as wide as a degree of latitude only on its standard parallel, so away from it a river
 * running north-south comes out a little wider than one running east-west.
 *
 * @param degreesPerBlock latitude degrees one block spans
 * @param minWidthBlocks  width of the smallest visible river, in blocks; {@code 1} or less widens nothing
 * @param blocksPerKm     the world's scale, which the importer used for its own one-block floor
 */
public record RiverWidening(double degreesPerBlock, double minWidthBlocks, double blocksPerKm) {

    /** Rivers exactly as prepared. */
    public static final RiverWidening NONE = new RiverWidening(0.0, 1.0, 1.0);

    /** The discharge that gets {@code minWidthBlocks}: the lowest the default config renders. */
    static final double REFERENCE_DISCHARGE = 40.0;
    /** The importer's width per square root of discharge, in metres. */
    private static final double WIDTH_FACTOR = 7.2;

    /** @param blocksPerKm {@code scale.blocks-per-km} */
    public static RiverWidening of(double minWidthBlocks, double blocksPerKm) {
        double metresPerBlock = 1_000.0 / blocksPerKm;
        double degreesPerBlock = Math.toDegrees(metresPerBlock / Projection.EARTH_RADIUS_METERS);
        return new RiverWidening(degreesPerBlock, minWidthBlocks, blocksPerKm);
    }

    /** Target width of a river carrying this discharge, in blocks. */
    double widthBlocks(double dischargeCubicMetresPerSecond) {
        double discharge = Double.isFinite(dischargeCubicMetresPerSecond)
                ? Math.max(REFERENCE_DISCHARGE, dischargeCubicMetresPerSecond) : REFERENCE_DISCHARGE;
        return minWidthBlocks * (1.0 + 0.5 * Math.log10(discharge / REFERENCE_DISCHARGE));
    }

    /** How far to buffer this feature's prepared polygon, in degrees; {@code 0} for anything but a river. */
    double extraRadiusDegrees(LazySqliteWaterProvider.CatalogEntry entry) {
        if (entry.type() != WaterType.RIVER || !(minWidthBlocks > 1.0) || !(degreesPerBlock > 0.0)) {
            return 0.0;
        }
        double discharge = entry.dischargeCubicMetresPerSecond();
        double target = widthBlocks(discharge) * degreesPerBlock / 2.0;
        // The importer's own radius: its width in metres over the metres in a longitude degree at
        // the river's latitude, so a river further from the equator is already a little wider.
        double preparedMetres = Math.max(1_000.0 / blocksPerKm,
                WIDTH_FACTOR * Math.sqrt(Math.max(0.0, Double.isFinite(discharge) ? discharge : 0.0)));
        double latitude = (entry.envelope().getMinY() + entry.envelope().getMaxY()) / 2.0;
        double cosine = Math.max(0.01, Math.abs(Math.cos(Math.toRadians(latitude))));
        double prepared = preparedMetres / (2.0 * 111_320.0 * cosine);
        return Math.max(0.0, target - prepared);
    }
}
