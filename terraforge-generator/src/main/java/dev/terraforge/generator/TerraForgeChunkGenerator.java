package dev.terraforge.generator;

import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.core.terrain.VerticalScale;
import dev.terraforge.generator.biome.BiomeMapper;
import dev.terraforge.generator.pipeline.ChunkSampler;
import dev.terraforge.generator.pipeline.TerrainPipeline;
import dev.terraforge.generator.surface.SurfacePalette;
import java.util.Random;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/**
 * Paper adapter: turns resolved {@link TerrainSample}s into blocks.
 *
 * <p>All geography has already happened by the time this class runs. It knows a surface height, a
 * water level and a biome per column, and its only job is to stack blocks accordingly -- which is
 * why it contains no noise, no seed and no randomness. Two servers with the same data and config
 * generate byte-identical worlds.
 *
 * <p><strong>Structures are off and stay off.</strong> Caves, ores and vegetation are vanilla's,
 * because they are natural and vanilla already places them well underground and on the surface;
 * villages, temples, mineshafts and the rest are disabled, because a real Earth has none of them
 * until players build them.
 */
public final class TerraForgeChunkGenerator extends ChunkGenerator {

    private final TerrainPipeline pipeline;
    private final VerticalScale verticalScale;
    private final BiomeMapper biomeMapper;
    private final int bedrockThickness;
    private final boolean vegetation;

    public TerraForgeChunkGenerator(TerrainPipeline pipeline, VerticalScale verticalScale,
                                    BiomeMapper biomeMapper, int bedrockThickness, boolean vegetation) {
        this.pipeline = pipeline;
        this.verticalScale = verticalScale;
        this.biomeMapper = biomeMapper;
        this.bedrockThickness = Math.max(1, bedrockThickness);
        this.vegetation = vegetation;
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunk) {
        ChunkSampler.ChunkSamples samples = pipeline.sampleChunk(chunkX, chunkZ);
        int floor = chunk.getMinHeight() + bedrockThickness;
        int ceiling = chunk.getMaxHeight() - 1;

        for (int localZ = 0; localZ < ChunkSampler.ChunkSamples.SIZE; localZ++) {
            for (int localX = 0; localX < ChunkSampler.ChunkSamples.SIZE; localX++) {
                TerrainSample sample = samples.at(localX, localZ);
                int surfaceY = Math.clamp(sample.surfaceY(), floor, ceiling);

                chunk.setRegion(localX, chunk.getMinHeight(), localZ,
                        localX + 1, floor, localZ + 1, Material.STONE);
                chunk.setRegion(localX, floor, localZ, localX + 1, surfaceY + 1, localZ + 1, Material.STONE);

                if (sample.isWater()) {
                    int waterY = Math.clamp(sample.waterSurfaceY(), floor, ceiling);
                    if (waterY > surfaceY) {
                        chunk.setRegion(localX, surfaceY + 1, localZ,
                                localX + 1, waterY + 1, localZ + 1, Material.WATER);
                    }
                }
            }
        }
    }

    @Override
    public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunk) {
        ChunkSampler.ChunkSamples samples = pipeline.sampleChunk(chunkX, chunkZ);
        int floor = chunk.getMinHeight() + bedrockThickness;
        int ceiling = chunk.getMaxHeight() - 1;

        for (int localZ = 0; localZ < ChunkSampler.ChunkSamples.SIZE; localZ++) {
            for (int localX = 0; localX < ChunkSampler.ChunkSamples.SIZE; localX++) {
                TerrainSample sample = samples.at(localX, localZ);
                int surfaceY = Math.clamp(sample.surfaceY(), floor, ceiling);
                SurfacePalette.Layers layers = SurfacePalette.forBiome(sample.biome());

                chunk.setBlock(localX, surfaceY, localZ, layers.top());
                int fillerBottom = Math.max(floor, surfaceY - SurfacePalette.FILLER_DEPTH);
                if (surfaceY > fillerBottom) {
                    chunk.setRegion(localX, fillerBottom, localZ, localX + 1, surfaceY, localZ + 1,
                            layers.filler());
                }
            }
        }
    }

    @Override
    public void generateBedrock(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunk) {
        chunk.setRegion(0, chunk.getMinHeight(), 0, 16, chunk.getMinHeight() + bedrockThickness, 16,
                Material.BEDROCK);
    }

    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        ChunkSampler.ChunkSamples samples = pipeline.sampleChunk(x >> 4, z >> 4);
        TerrainSample sample = samples.at(x & 15, z & 15);
        int surface = sample.surfaceY();
        return sample.isWater() ? Math.max(surface, sample.waterSurfaceY()) : surface;
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new TerraForgeBiomeProvider(pipeline, biomeMapper);
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false; // the terrain comes from the DEM, not from vanilla's noise router
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false; // surface materials come from real land cover, see SurfacePalette
    }

    @Override
    @SuppressWarnings("deprecation") // the flag is deprecated upstream; the hook it guards is not
    public boolean shouldGenerateBedrock() {
        return false; // written by generateBedrock, at the configured thickness
    }

    @Override
    public boolean shouldGenerateCaves() {
        return true; // caves are natural, and they are underground where geography has nothing to say
    }

    @Override
    public boolean shouldGenerateDecorations() {
        // Ores, trees, grass and flowers, placed by vanilla according to the biome we chose.
        return vegetation;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return true; // a living planet
    }

    @Override
    public boolean shouldGenerateStructures() {
        // Villages, temples, mineshafts, strongholds, ruins: every one of them is man-made, and
        // this is the switch that guarantees TerraForge never places one.
        return false;
    }

    /** Sea level in blocks, as the vertical scale defines it. */
    public int seaLevel() {
        return verticalScale.seaLevel();
    }
}
