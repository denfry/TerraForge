package dev.terraforge.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.coord.WorldExtent;
import dev.terraforge.core.data.KarstProvider;
import dev.terraforge.core.projection.EquirectangularProjection;
import dev.terraforge.core.terrain.VerticalScale;
import dev.terraforge.generator.biome.BiomeMapper;
import dev.terraforge.generator.pipeline.TerrainPipeline;
import java.util.Random;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator.ChunkData;
import org.bukkit.generator.WorldInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlanetEdgeTest {

    private final TerrainPipeline pipeline = mock(TerrainPipeline.class);

    private TerraForgeChunkGenerator generator(WorldExtent extent) {
        return new TerraForgeChunkGenerator(pipeline, new VerticalScale(64, -64, 320, 1.0, 100.0),
                mock(BiomeMapper.class), 3, new TerraForgeChunkGenerator.Features(false, false, false),
                new CoordinateTransformer(new EquirectangularProjection(0), new GeoPoint(0, 0), 1),
                KarstProvider.absent(), extent);
    }

    @Test
    @DisplayName("a chunk past the edge is void: cleared, never sampled, no bedrock")
    void beyondTheEdgeIsVoid() {
        TerraForgeChunkGenerator generator = generator(new WorldExtent(-20, -10, 19, 9));
        ChunkData chunk = mock(ChunkData.class);
        when(chunk.getMinHeight()).thenReturn(-64);
        when(chunk.getMaxHeight()).thenReturn(320);
        WorldInfo world = mock(WorldInfo.class);
        when(world.getMinHeight()).thenReturn(-64);

        generator.generateNoise(world, new Random(0), 5, 5, chunk);
        generator.generateSurface(world, new Random(0), 5, 5, chunk);
        generator.generateBedrock(world, new Random(0), 5, 5, chunk);

        verify(chunk).setRegion(0, -64, 0, 16, 320, 16, Material.AIR);
        verify(chunk, never()).setRegion(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
                org.mockito.ArgumentMatchers.eq(Material.BEDROCK));
        verify(pipeline, never()).sampleChunk(anyInt(), anyInt());
        assertThat(generator.getBaseHeight(world, new Random(0), 100, 100, HeightMap.WORLD_SURFACE))
                .isEqualTo(-64);
    }

    @Test
    @DisplayName("without a border the generator has no edge")
    void noBorderNoEdge() {
        assertThat(generator(null).extent()).isNull();
    }
}
