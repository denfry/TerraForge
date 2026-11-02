package dev.terraforge.generator;

import dev.terraforge.core.terrain.ClimateBiome;
import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.generator.biome.BiomeMapper;
import dev.terraforge.generator.pipeline.ChunkSampler;
import dev.terraforge.generator.pipeline.TerrainPipeline;
import java.util.Arrays;
import java.util.List;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

/**
 * Tells Paper which biome each column is, using the same samples the terrain was built from.
 *
 * <p>Biomes are a column property here, not a 3D one: the Earth's vegetation belongs to a place,
 * not to an altitude band within it, and querying per column keeps a cave under a forest inside
 * that forest.
 */
public final class TerraForgeBiomeProvider extends BiomeProvider {

    /**
     * Every biome the mapper can return. Paper needs the complete set up front to build the world's
     * biome registry, so it is derived from the mapper rather than maintained by hand -- a new
     * climate biome cannot be forgotten here.
     */
    private final List<Biome> supported;

    private static final List<Double> PROBE_LATITUDES = List.of(0.0, 30.0, 45.0, 58.0, 63.0, 70.0, 80.0);
    /** How far a column's latitude wanders before it picks a frozen, cold or warm variant, in degrees. */
    private static final double LATITUDE_WANDER = 2.0;
    private static final int LATITUDE_WANDER_CELL = 48;
    private static final long LATITUDE_WANDER_SALT = 0x4C415457L;

    private final TerrainPipeline pipeline;
    private final BiomeMapper mapper;

    public TerraForgeBiomeProvider(TerrainPipeline pipeline, BiomeMapper mapper) {
        this.pipeline = pipeline;
        this.mapper = mapper;
        this.supported = Arrays.stream(ClimateBiome.values())
                // Both variant-selecting inputs matter, so probe across their range.
                .flatMap(climate -> PROBE_LATITUDES.stream().flatMap(latitude -> java.util.stream.Stream.of(
                        mapper.toMinecraft(climate, 0.0, latitude),
                        mapper.toMinecraft(climate, 5000.0, latitude))))
                .distinct()
                .toList();
    }

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        var samples = pipeline.sampleChunk(x >> 4, z >> 4);
        TerrainSample sample = samples.at(x & 15, z & 15);
        // Variants switch at fixed parallels (snowy taiga at 60 degrees, cold ocean, lukewarm seas);
        // without wander each switch is a straight line across a continent.
        double wander = (dev.terraforge.generator.noise.CellNoise.smooth(x, z, LATITUDE_WANDER_CELL,
                LATITUDE_WANDER_SALT) * 2.0 - 1.0) * LATITUDE_WANDER;
        return mapper.toMinecraft(sample.biome(), sample.elevationMeters(), latitudeOf(samples, z & 15) + wander);
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        return supported;
    }

    /**
     * Latitude of a column, interpolated across the chunk's geographic bounds. The sample itself
     * does not carry one, and latitude decides the frozen and snowy variants, so it is recovered
     * from the bounds rather than guessed at.
     */
    private double latitudeOf(ChunkSampler.ChunkSamples samples, int localZ) {
        var bounds = samples.bounds();
        double northToSouth = (localZ + 0.5) / ChunkSampler.ChunkSamples.SIZE;
        return bounds.maxLatitude() - northToSouth * (bounds.maxLatitude() - bounds.minLatitude());
    }
}
