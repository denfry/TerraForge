package dev.terraforge.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.data.KarstProvider;
import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.core.projection.EquirectangularProjection;
import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.core.terrain.VerticalScale;
import dev.terraforge.generator.biome.BiomeMapper;
import dev.terraforge.generator.pipeline.ChunkSampler;
import dev.terraforge.generator.pipeline.TerrainPipeline;
import java.util.Random;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/**
 * The origin surface is fully deterministic (DEM-driven, no noise), so {@code getFixedSpawnLocation}
 * must skip Paper's random safe-spawn scan entirely: it reads the same base height {@link
 * TerraForgeChunkGenerator#getBaseHeight} exposes, clamps it into the world, and never touches the
 * {@link Random} it is handed.
 */
class FixedSpawnTest {

    private static final int SURFACE_Y = 90;

    @Test
    void spawnIsFixedAtTheOriginColumnAndNeverConsultsRandom() {
        TerraForgeChunkGenerator generator = generatorWithOriginSurfaceAt(SURFACE_Y);

        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);

        Random random = mock(Random.class);

        Location spawn = generator.getFixedSpawnLocation(world, random);

        assertThat(spawn.getWorld()).isSameAs(world);
        assertThat(spawn.getX()).isEqualTo(0.5);
        assertThat(spawn.getZ()).isEqualTo(0.5);
        assertThat(spawn.getY()).isEqualTo(SURFACE_Y + 1);

        verifyNoInteractions(random);
    }

    @Test
    void spawnYIsClampedIntoTheWorldsVerticalBounds() {
        World lowWorld = mock(World.class);
        when(lowWorld.getMinHeight()).thenReturn(-64);
        when(lowWorld.getMaxHeight()).thenReturn(320);
        TerraForgeChunkGenerator belowFloor = generatorWithOriginSurfaceAt(-70);
        assertThat(belowFloor.getFixedSpawnLocation(lowWorld, mock(Random.class)).getY()).isEqualTo(-63);

        TerraForgeChunkGenerator aboveCeiling = generatorWithOriginSurfaceAt(400);
        assertThat(aboveCeiling.getFixedSpawnLocation(lowWorld, mock(Random.class)).getY()).isEqualTo(319);
    }

    /** A generator whose pipeline always resolves the origin column to a fixed, dry surfaceY. */
    private static TerraForgeChunkGenerator generatorWithOriginSurfaceAt(int surfaceY) {
        TerrainSample origin = new TerrainSample(surfaceY, surfaceY, WaterType.NONE, 0,
                dev.terraforge.core.data.LandcoverProvider.LandcoverClass.UNKNOWN, ClimateBiome.TEMPERATE_FOREST, false);
        TerrainSample[] samples = new TerrainSample[16 * 16];
        java.util.Arrays.fill(samples, origin);
        ChunkSampler.ChunkSamples chunkSamples = new ChunkSampler.ChunkSamples(0, 0, GeoBounds.world(), samples);

        TerrainPipeline pipeline = mock(TerrainPipeline.class);
        when(pipeline.sampleChunk(0, 0)).thenReturn(chunkSamples);

        VerticalScale verticalScale = new VerticalScale(64, -64, 320, 1.0, 100.0);
        CoordinateTransformer transformer = new CoordinateTransformer(
                new EquirectangularProjection(0), new GeoPoint(0, 0), 1);

        return new TerraForgeChunkGenerator(pipeline, verticalScale, mock(BiomeMapper.class), 3, false, false,
                transformer, KarstProvider.absent());
    }
}
