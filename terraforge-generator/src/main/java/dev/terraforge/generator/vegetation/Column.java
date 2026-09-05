package dev.terraforge.generator.vegetation;

import dev.terraforge.core.terrain.TerrainSample;
import org.bukkit.Material;

/**
 * One column as the vegetation pass sees it: its own sample and ground, plus what lies around it.
 *
 * <p>Plants answer to their neighbourhood as much as to their own square. Reeds need water beside
 * them, a willow wants a river bank, a palm a warm coast, a wheat field level ground. The populator
 * reads those facts from the samples around the column and hands them over here, so
 * {@link VegetationPlan} stays a pure function of one value and one random draw.
 *
 * @param sample             the column's terrain sample
 * @param ground             the block the column's surface is made of, as generated
 * @param x                  world block X
 * @param z                  world block Z
 * @param flat               every neighbouring column within one block shares this surface height
 * @param freshWaterDistance Chebyshev distance in blocks to the nearest river or lake column, or
 *                           {@link #NO_WATER} when none lies within {@link #RADIUS}
 * @param freshWaterAtLevel  a river or lake column directly beside this one holds water at the
 *                           height of this column's surface block, which is what sugar cane needs
 * @param warmNeighbourhood  a tropical or arid biome lies within {@link #RADIUS}; this is how a
 *                           beach, which carries no climate of its own, learns whether it is warm
 */
public record Column(TerrainSample sample, Material ground, int x, int z, boolean flat,
                     int freshWaterDistance, boolean freshWaterAtLevel, boolean warmNeighbourhood) {

    /** How far around a column the populator looks for water and climate. */
    public static final int RADIUS = 6;
    /** {@link #freshWaterDistance} when no fresh water lies within {@link #RADIUS}. */
    public static final int NO_WATER = Integer.MAX_VALUE;

    /** A column on level ground with nothing notable around it. */
    public static Column plain(TerrainSample sample, Material ground, int x, int z) {
        return new Column(sample, ground, x, z, true, NO_WATER, false, false);
    }

    public Column onSlope() {
        return new Column(sample, ground, x, z, false, freshWaterDistance, freshWaterAtLevel, warmNeighbourhood);
    }

    public Column nearFreshWater(int distance, boolean atLevel) {
        return new Column(sample, ground, x, z, flat, distance, atLevel, warmNeighbourhood);
    }

    public Column inWarmNeighbourhood() {
        return new Column(sample, ground, x, z, flat, freshWaterDistance, freshWaterAtLevel, true);
    }

    /** Within a few blocks of a river or lake: the riparian strip. */
    public boolean riparian() {
        return freshWaterDistance <= 3;
    }
}
