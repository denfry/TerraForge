package dev.terraforge.generator;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.data.KarstProvider;
import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.core.projection.EquirectangularProjection;
import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.core.terrain.VerticalScale;
import dev.terraforge.generator.biome.BiomeMapper;
import dev.terraforge.generator.pipeline.ChunkSampler;
import dev.terraforge.generator.pipeline.TerrainPipeline;
import java.util.Arrays;
import java.util.Random;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator.ChunkData;
import org.bukkit.generator.WorldInfo;
import org.junit.jupiter.api.Test;

/**
 * Regression for the spawn world losing its builds: to keep the planet inside the 0..256 a 1.16
 * client can see, the height datapack shrank the overworld dimension type -- and a Multiverse spawn
 * world shares that type, so everything it had below y=0 or above y=255 was cut off. The terrain is
 * now kept to a band inside an unchanged vanilla-height world instead.
 */
class GeneratedBandTest {

    @Test
    void theTerrainStartsAtTheBandNotAtTheWorldFloor() {
        TerrainPipeline pipeline = mock(TerrainPipeline.class);
        TerrainSample[] columns = new TerrainSample[256];
        Arrays.fill(columns, new TerrainSample(600.0, 100, WaterType.NONE, 0, LandcoverClass.GRASSLAND,
                ClimateBiome.GRASSLAND, false));
        when(pipeline.sampleChunk(anyInt(), anyInt()))
                .thenReturn(new ChunkSampler.ChunkSamples(0, 0, null, columns));
        TerraForgeChunkGenerator generator = new TerraForgeChunkGenerator(pipeline,
                new VerticalScale(63, 0, 256, 1.0, 15.0, 1300.0), mock(BiomeMapper.class), 1,
                new TerraForgeChunkGenerator.Features(false, false, false),
                new CoordinateTransformer(new EquirectangularProjection(0), new GeoPoint(0, 0), 1),
                KarstProvider.absent());
        ChunkData chunk = mock(ChunkData.class);
        when(chunk.getMinHeight()).thenReturn(-64); // vanilla's height: no datapack needed
        when(chunk.getMaxHeight()).thenReturn(320);
        WorldInfo world = mock(WorldInfo.class);

        generator.generateNoise(world, new Random(0), 0, 0, chunk);
        generator.generateBedrock(world, new Random(0), 0, 0, chunk);

        verify(chunk).setRegion(0, 0, 0, 16, 1, 16, Material.BEDROCK);
        verify(chunk).setRegion(3, 0, 5, 4, 1, 6, Material.STONE); // under the bedrock layer, from y=0
        verify(chunk).setRegion(3, 1, 5, 4, 101, 6, Material.STONE);
        // Nothing below the band: the world under y=0 stays air.
        verify(chunk, never()).setRegion(anyInt(), eq(-64), anyInt(), anyInt(), anyInt(), anyInt(),
                eq(Material.STONE));
        verify(chunk, never()).setRegion(anyInt(), eq(-64), anyInt(), anyInt(), anyInt(), anyInt(),
                eq(Material.BEDROCK));
    }
}
