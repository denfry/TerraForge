package dev.terraforge.generator.underground;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.generator.pipeline.ChunkSampler;
import dev.terraforge.generator.surface.SurfacePalette;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UndergroundGeneratorTest {

    /** The frame a 1.16 client sees whole: y 0..255, sea at 63, one layer of bedrock. */
    private static final int LEGACY_FLOOR = 1;
    private static final int LEGACY_SEA = 63;
    private static final int LEGACY_TOP = 256;

    private static final TerraForgeConfig.UndergroundSection ALL_ON =
            new TerraForgeConfig.UndergroundSection(true, 1.0, true, true, 7.0);

    record Placed(int x, int y, int z, Material material) {
    }

    /**
     * Regression for ores that old clients could not see: EarthFix shifted vanilla's 1.18 table under
     * a sea level of 0, which put diamonds near y=-120 -- below the y=0 a 1.16 client is cut off at.
     * In the legacy frame every ore must land inside 0..255 and at vanilla 1.16's heights.
     */
    @Test
    void everyOreLandsWhereA116ClientCanSeeIt() {
        UndergroundGenerator generator = new UndergroundGenerator(ALL_ON, LEGACY_FLOOR, LEGACY_SEA, LEGACY_TOP);
        Map<Mineral, int[]> range = new EnumMap<>(Mineral.class);
        for (int cx = 0; cx < 12; cx++) {
            for (int cz = 0; cz < 12; cz++) {
                // Mountain land so emerald's altitude rule is met and there is rock up to y=180.
                for (Placed p : generate(generator, land(cx, cz, 184, 2500.0))) {
                    Mineral mineral = mineralOf(p.material());
                    if (mineral != null) {
                        range.computeIfAbsent(mineral, m -> new int[] {Integer.MAX_VALUE, Integer.MIN_VALUE});
                        range.get(mineral)[0] = Math.min(range.get(mineral)[0], p.y());
                        range.get(mineral)[1] = Math.max(range.get(mineral)[1], p.y());
                    }
                }
            }
        }
        assertThat(range.keySet()).as("every ore and pocket appears").containsAll(EnumSet.allOf(Mineral.class));
        range.values().forEach(r -> assertThat(r[0]).isGreaterThanOrEqualTo(LEGACY_FLOOR));
        range.values().forEach(r -> assertThat(r[1]).isLessThan(LEGACY_TOP));
        // Vanilla 1.16 heights, give or take the few blocks a vein spreads from its centre.
        assertThat(range.get(Mineral.DIAMOND)[1]).isLessThanOrEqualTo(15 + 3);
        assertThat(range.get(Mineral.REDSTONE)[1]).isLessThanOrEqualTo(15 + 3);
        assertThat(range.get(Mineral.GOLD)[1]).isLessThanOrEqualTo(31 + 3);
        assertThat(range.get(Mineral.EMERALD)[1]).isLessThanOrEqualTo(31);
        assertThat(range.get(Mineral.COAL)[1]).isGreaterThan(127); // mountains get coal too
    }

    @Test
    @DisplayName("nothing is placed outside the stone: soil, water and air are never touched")
    void onlyStoneChanges() {
        UndergroundGenerator generator = new UndergroundGenerator(ALL_ON, LEGACY_FLOOR, LEGACY_SEA, LEGACY_TOP);
        int surface = 90;
        for (int cx = 0; cx < 6; cx++) {
            for (Placed p : generate(generator, land(cx, 3, surface, 400.0))) {
                assertThat(p.y()).isGreaterThanOrEqualTo(LEGACY_FLOOR);
                assertThat(p.y()).isLessThan(surface - SurfacePalette.FILLER_DEPTH);
            }
        }
    }

    @Test
    void theSameChunkAlwaysGeneratesTheSameUnderground() {
        UndergroundGenerator generator = new UndergroundGenerator(ALL_ON, LEGACY_FLOOR, LEGACY_SEA, LEGACY_TOP);
        var samples = land(17, -42, 120, 800.0);
        assertThat(generate(generator, samples)).isEqualTo(generate(generator, samples))
                .isEqualTo(generate(new UndergroundGenerator(ALL_ON, LEGACY_FLOOR, LEGACY_SEA, LEGACY_TOP), samples));
    }

    @Test
    @DisplayName("caves stay under the roof of the ground and never reach under nearby water")
    void cavesNeverOpenOrReachWater() {
        var manyCaves = new TerraForgeConfig.UndergroundSection(false, 1.0, false, true, 1.0);
        UndergroundGenerator generator = new UndergroundGenerator(manyCaves, LEGACY_FLOOR, LEGACY_SEA, LEGACY_TOP);
        int landSurface = 110;
        int bed = 55;
        int carved = 0;
        for (int cx = 0; cx < 10; cx++) {
            for (int cz = 0; cz < 10; cz++) {
                // A lake over the columns with local x >= 12, and over the whole ring east of the chunk.
                var samples = chunk(cx, cz, 4, (x, z) -> x >= 12
                        ? water(bed, 60) : ground(landSurface, 900.0));
                for (Placed p : generate(generator, samples)) {
                    assertThat(p.material()).isEqualTo(Material.CAVE_AIR);
                    carved++;
                    assertThat(p.y()).isLessThanOrEqualTo(landSurface - WormCaves.ROOF);
                    if (p.x() >= 12 - WormCaves.WATER_MARGIN) {
                        assertThat(p.y()).as("under or beside the lake at x=%d", p.x())
                                .isLessThanOrEqualTo(bed - WormCaves.ROOF - WormCaves.WATER_MARGIN);
                    }
                }
            }
        }
        assertThat(carved).as("at rarity 1 the caves must exist at all").isGreaterThan(1000);
    }

    @Test
    @DisplayName("a deeper world keeps the bands' depth below the sea and continues them to the floor")
    void bandsMoveWithTheSea() {
        List<OreBand> legacy = OreTable.ores(LEGACY_FLOOR, LEGACY_SEA, LEGACY_TOP);
        assertThat(band(legacy, "diamond").minY()).isEqualTo(LEGACY_FLOOR);
        assertThat(band(legacy, "diamond").maxY()).isEqualTo(15);
        assertThat(band(legacy, "coal").count()).isEqualTo(20.0);
        assertThat(legacy).noneMatch(b -> b.name().endsWith("_deep"));

        // The old production frame: sea at 0, floor at -504.
        List<OreBand> deep = OreTable.ores(-504, 0, 512);
        assertThat(band(deep, "diamond").minY()).isEqualTo(-63);
        assertThat(band(deep, "diamond").maxY()).isEqualTo(-48);
        OreBand deepDiamond = band(deep, "diamond_deep");
        assertThat(deepDiamond.minY()).isEqualTo(-504);
        assertThat(deepDiamond.maxY()).isEqualTo(-64);
        // Same density per block as vanilla's own layer: one vein per sixteen layers.
        assertThat(deepDiamond.count()).isCloseTo(441 / 16.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    private static OreBand band(List<OreBand> bands, String name) {
        return bands.stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
    }

    private static List<Placed> generate(UndergroundGenerator generator, ChunkSampler.ChunkSamples samples) {
        List<Placed> placed = new ArrayList<>();
        generator.generate(samples, LEGACY_FLOOR, LEGACY_TOP - 1,
                (x, y, z, material) -> placed.add(new Placed(x, y, z, material)));
        return placed;
    }

    private static Mineral mineralOf(Material material) {
        for (Mineral mineral : Mineral.values()) {
            if (mineral.material() == material) {
                return mineral;
            }
        }
        return null;
    }

    private static ChunkSampler.ChunkSamples land(int chunkX, int chunkZ, int surfaceY, double elevation) {
        return chunk(chunkX, chunkZ, 4, (x, z) -> ground(surfaceY, elevation));
    }

    private static TerrainSample ground(int surfaceY, double elevation) {
        return new TerrainSample(elevation, surfaceY, WaterType.NONE, 0, LandcoverClass.GRASSLAND,
                ClimateBiome.MOUNTAIN_MEADOW, false);
    }

    private static TerrainSample water(int bedY, int surfaceY) {
        return new TerrainSample(-20.0, bedY, WaterType.LAKE, surfaceY, LandcoverClass.PERMANENT_WATER,
                ClimateBiome.LAKE, false);
    }

    /** A chunk and a ring of {@code margin} columns around it, from a function of local coordinates. */
    private static ChunkSampler.ChunkSamples chunk(int chunkX, int chunkZ, int margin,
                                                   BiFunction<Integer, Integer, TerrainSample> column) {
        TerrainSample[] samples = new TerrainSample[256];
        int width = 16 + 2 * margin;
        int[] surface = new int[width * width];
        boolean[] wet = new boolean[width * width];
        for (int gz = 0; gz < width; gz++) {
            for (int gx = 0; gx < width; gx++) {
                int x = gx - margin;
                int z = gz - margin;
                TerrainSample sample = column.apply(x, z);
                surface[gz * width + gx] = sample.surfaceY();
                wet[gz * width + gx] = sample.isWater();
                if (x >= 0 && x < 16 && z >= 0 && z < 16) {
                    samples[z * 16 + x] = sample;
                }
            }
        }
        return new ChunkSampler.ChunkSamples(chunkX, chunkZ, null, samples,
                new ChunkSampler.Neighbourhood(margin, surface, wet));
    }
}
