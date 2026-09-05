package dev.terraforge.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.terraforge.core.cache.CacheManager;
import dev.terraforge.core.config.TerraForgeConfig;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.coord.MinecraftPos;
import dev.terraforge.core.data.ConstantLandcoverProvider;
import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.data.KarstProvider;
import dev.terraforge.core.data.WaterProvider;
import dev.terraforge.core.data.WaterProvider.WaterColumn;
import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.core.projection.EquirectangularProjection;
import dev.terraforge.core.terrain.VerticalScale;
import java.util.Random;
import java.util.TreeMap;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator.ChunkData;
import org.junit.jupiter.api.Test;

/**
 * Chunk generation driven end to end over the columns the world-generator audit measured.
 *
 * <p>Not a unit test of one stage: this runs {@link TerraForgeChunkGenerator#generateNoise} and
 * {@link TerraForgeChunkGenerator#generateSurface} and reads back the blocks a player would stand
 * on, in the audited world's own vertical frame ({@code min-y -512}, {@code sea-level 0},
 * {@code 20 m} per block, one block per kilometre). At that scale a metre of error is a twentieth of
 * a block, which is why the defect was invisible at one metre per block and removed 277 blocks --
 * 5,540 m -- of Tibetan plateau in production.
 *
 * <p>Reference column: 30.4933 N 84.0688 E, DEM 5,440.5 m. The audit dumped
 * {@code -4..-1:sand 0:water 1..40:air} there, under a mountain that should reach y=272.
 */
class AuditedColumnsTest {

    private static final VerticalScale AUDITED_SCALE = new VerticalScale(0, -512, 512, 1.0, 20.0);
    private static final double TIBET_LATITUDE = 30.4933;
    private static final double TIBET_LONGITUDE = 84.0688;
    private static final double TIBET_METRES = 5440.5;

    @Test
    void theTibetanPlateauSurvivesALakeWhoseAltitudeNobodyKnows() {
        Column column = generate(TIBET_METRES,
                new WaterColumn(WaterType.LAKE, ElevationProvider.NO_DATA, 0.0));

        assertThat(AUDITED_SCALE.toBlockY(TIBET_METRES)).isEqualTo(272);
        assertThat(column.highestNonAir()).isEqualTo(272);
        assertThat(column.at(0)).isEqualTo(Material.STONE);
        // The artifact the audit dumped: one water block at sea level and air all the way up.
        assertThat(column.count(Material.WATER)).isZero();
        assertThat(column.at(1)).isNotEqualTo(Material.AIR);
    }

    @Test
    void aLakeDeclaredAtSeaLevelIsRejectedInsteadOfFlatteningTheColumn() {
        Column column = generate(TIBET_METRES, new WaterColumn(WaterType.LAKE, 0.0, 0.0));

        assertThat(column.highestNonAir()).isEqualTo(272);
        assertThat(column.count(Material.WATER)).isZero();
    }

    @Test
    void theSameLakeAtItsRealAltitudeFloatsOnTopOfThePlateau() {
        Column column = generate(TIBET_METRES, new WaterColumn(WaterType.LAKE, TIBET_METRES, 60.0));

        assertThat(column.highestNonAir()).isEqualTo(272);
        assertThat(column.at(272)).isEqualTo(Material.WATER);
        // 60 m of average depth is three blocks here, the default lake floor; the bed noise may
        // deepen that to six. Either way the bed is rock five kilometres up.
        assertThat(column.count(Material.WATER)).isBetween(3L, 6L);
        assertThat(column.at(266)).isNotEqualTo(Material.WATER);
    }

    @Test
    void anOceanColumnIsWaterFromTheSeaBedUpToSeaLevel() {
        Column column = generate(-3000.0, WaterColumn.OCEAN);

        assertThat(column.highestNonAir()).isEqualTo(AUDITED_SCALE.seaLevel());
        assertThat(column.at(AUDITED_SCALE.seaLevel())).isEqualTo(Material.WATER);
        assertThat(column.at(AUDITED_SCALE.toBlockY(-3000.0))).isNotEqualTo(Material.WATER);
    }

    // --- harness ------------------------------------------------------------

    /**
     * Generates the chunk holding the reference column through the assembled stack and returns that
     * column's blocks.
     */
    private static Column generate(double demMetres, WaterColumn water) {
        CoordinateTransformer transformer = new CoordinateTransformer(
                EquirectangularProjection.plateCarree(), new GeoPoint(51.0, 10.0), 1.0);
        TerrainStack stack = TerrainStack.create(TerraForgeConfig.defaults(), transformer,
                AUDITED_SCALE, elevation(demMetres), new CacheManager(64), water(water),
                ConstantLandcoverProvider.unknown(), KarstProvider.absent());

        MinecraftPos position = transformer.toMinecraft(TIBET_LATITUDE, TIBET_LONGITUDE);
        int blockX = (int) Math.floor(position.x());
        int blockZ = (int) Math.floor(position.z());
        Column column = new Column(blockX & 15, blockZ & 15);

        ChunkData chunk = mock(ChunkData.class);
        when(chunk.getMinHeight()).thenReturn(AUDITED_SCALE.minY());
        when(chunk.getMaxHeight()).thenReturn(AUDITED_SCALE.maxY());
        column.record(chunk);

        TerraForgeChunkGenerator generator = stack.chunkGenerator();
        generator.generateNoise(null, new Random(0), blockX >> 4, blockZ >> 4, chunk);
        generator.generateSurface(null, new Random(0), blockX >> 4, blockZ >> 4, chunk);
        return column;
    }

    /**
     * The blocks written to one column, captured from the generator's own {@code setRegion} and
     * {@code setBlock} calls.
     *
     * <p>Replayed into a map rather than verified as calls: {@code setRegion} is a range write, so
     * asserting on invocations would test the calls the generator made instead of the terrain a
     * player would find, and the audited defect was only visible in the latter.
     */
    private static final class Column {

        private final int localX;
        private final int localZ;
        private final TreeMap<Integer, Material> blocks = new TreeMap<>();

        private Column(int localX, int localZ) {
            this.localX = localX;
            this.localZ = localZ;
        }

        void record(ChunkData chunk) {
            org.mockito.Mockito.doAnswer(invocation -> {
                write(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5),
                        invocation.getArgument(6));
                return null;
            }).when(chunk).setRegion(org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.any(Material.class));

            org.mockito.Mockito.doAnswer(invocation -> {
                int x = invocation.getArgument(0);
                int z = invocation.getArgument(2);
                if (x == localX && z == localZ) {
                    blocks.put(invocation.getArgument(1), invocation.getArgument(3));
                }
                return null;
            }).when(chunk).setBlock(org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.any(Material.class));
        }

        private void write(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Material material) {
            if (localX < minX || localX >= maxX || localZ < minZ || localZ >= maxZ) {
                return;
            }
            for (int y = minY; y < maxY; y++) {
                blocks.put(y, material);
            }
        }

        Material at(int y) {
            return blocks.getOrDefault(y, Material.AIR);
        }

        int highestNonAir() {
            return blocks.descendingMap().entrySet().stream()
                    .filter(entry -> entry.getValue() != Material.AIR)
                    .mapToInt(java.util.Map.Entry::getKey)
                    .findFirst()
                    .orElse(Integer.MIN_VALUE);
        }

        long count(Material material) {
            return blocks.values().stream().filter(material::equals).count();
        }
    }

    private static WaterProvider water(WaterColumn answer) {
        return new WaterProvider() {
            @Override
            public WaterType waterTypeAt(double latitude, double longitude) {
                return answer.type();
            }

            @Override
            public WaterColumn waterColumnAt(double latitude, double longitude, double knownElevationMeters) {
                return answer;
            }
        };
    }

    private static ElevationProvider elevation(double metres) {
        return new ElevationProvider() {
            @Override
            public double elevationAt(double latitude, double longitude) {
                return metres;
            }

            @Override
            public boolean hasCoverage(double latitude, double longitude) {
                return true;
            }

            @Override
            public GeoBounds coverage() {
                return GeoBounds.world();
            }

            @Override
            public boolean hasBathymetry() {
                return true;
            }
        };
    }
}
