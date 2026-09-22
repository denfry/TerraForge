package dev.terraforge.generator;

import dev.terraforge.core.terrain.TerrainSample;
import dev.terraforge.core.terrain.VerticalScale;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.WorldExtent;
import dev.terraforge.core.data.KarstProvider;
import dev.terraforge.generator.biome.BiomeMapper;
import dev.terraforge.generator.pipeline.ChunkSampler;
import dev.terraforge.generator.pipeline.TerrainPipeline;
import dev.terraforge.generator.surface.SurfacePalette;
import dev.terraforge.generator.underground.UndergroundGenerator;
import dev.terraforge.generator.vegetation.VegetationPlan;
import dev.terraforge.generator.vegetation.VegetationPopulator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

/**
 * Paper adapter: turns resolved {@link TerrainSample}s into blocks.
 *
 * <p>All geography has already happened by the time this class runs. It knows a surface height, a
 * water level and a biome per column, and its only job is to stack blocks accordingly -- which is
 * why it contains no noise, no seed and no randomness. Two servers with the same data and config
 * generate byte-identical worlds.
 *
 * <p><strong>Structures are off and stay off.</strong> Villages, temples, mineshafts and the rest
 * are man-made; a real Earth has none of them until players build them.
 *
 * <p><strong>Vanilla worldgen is off by default, and that is a correctness decision, not taste.</strong>
 * Paper's wrapper does not adopt this world's vertical frame: every vanilla stage still sees
 * {@code min_y -64}, {@code sea_level 63} and one metre per block, from
 * {@code overworld.json}. Enabling the carvers in a 20 m-per-block world therefore deletes
 * hundreds of metres of real rock per cave, lets carvers replace TerraForge's ocean with air
 * (vanilla's {@code overworld_carver_replaceables} includes water), and lets the aquifer reflood
 * the breach up to y=63 -- more than a kilometre of real elevation above this world's sea level.
 * {@link #shouldGenerateCaves()} and {@link #shouldGenerateDecorations()} report the two explicit
 * {@code generation.vanilla-*} switches and nothing else.
 */
public final class TerraForgeChunkGenerator extends ChunkGenerator {

    private static final System.Logger LOG = System.getLogger("TerraForge-Generator");
    private static final AtomicBoolean FIRST_DEM_CHUNK_LOGGED = new AtomicBoolean();

    /** Vanilla's own vertical frame, which every vanilla worldgen stage assumes. */
    private static final int VANILLA_SEA_LEVEL = 63;
    private static final int VANILLA_MIN_Y = -64;
    private static final double VANILLA_METERS_PER_BLOCK = 1.0;

    /** A routine vanilla cave's vertical extent, used only to state the damage in real metres. */
    private static final int VANILLA_CAVE_BLOCKS = 30;

    /** The frame warning is a property of the configuration, not of the world: once per JVM. */
    private static final AtomicBoolean VANILLA_FRAME_WARNED = new AtomicBoolean();

    private final TerrainPipeline pipeline;
    private final VerticalScale verticalScale;
    private final BiomeMapper biomeMapper;
    private final int bedrockThickness;
    private final Features features;
    private final KarstCaveCarver caveCarver;

    /** Ores, stone pockets and cave systems ({@code generation.underground}); {@code null} when all off. */
    private final UndergroundGenerator underground;

    /** One shared block state per material the underground pass writes, created on first use. */
    private volatile java.util.Map<Material, BlockData> undergroundPalette;

    /** The planet's edge when {@code world.border} is on; {@code null} generates without end. */
    private final WorldExtent extent;

    public TerraForgeChunkGenerator(TerrainPipeline pipeline, VerticalScale verticalScale,
                                    BiomeMapper biomeMapper, int bedrockThickness, Features features,
                                    CoordinateTransformer transformer, KarstProvider karst) {
        this(pipeline, verticalScale, biomeMapper, bedrockThickness, features, transformer, karst, null);
    }

    public TerraForgeChunkGenerator(TerrainPipeline pipeline, VerticalScale verticalScale,
                                    BiomeMapper biomeMapper, int bedrockThickness, Features features,
                                    CoordinateTransformer transformer, KarstProvider karst,
                                    WorldExtent extent) {
        this.extent = extent;
        this.pipeline = pipeline;
        this.verticalScale = verticalScale;
        this.biomeMapper = biomeMapper;
        this.bedrockThickness = Math.max(1, bedrockThickness);
        this.features = features;
        this.caveCarver = features.karstCaves() ? new KarstCaveCarver(transformer, karst, pipeline) : null;
        UndergroundGenerator below = new UndergroundGenerator(features.underground(),
                verticalScale.minY() + this.bedrockThickness, verticalScale.seaLevel(), verticalScale.maxY());
        this.underground = below.enabled() ? below : null;
        String warning = vanillaFrameWarning(verticalScale, features);
        if (warning != null && VANILLA_FRAME_WARNED.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING, warning);
        }
    }

    /**
     * The complaint to log when vanilla worldgen has been enabled in a world whose vertical frame is
     * not vanilla's, or {@code null} when the combination is sound.
     *
     * <p>Returned rather than logged directly so its wording is testable: this message is the only
     * warning an operator gets before a scaled world quietly fills its mountains with water.
     *
     * <p>A warning and not a refusal: running TerraForge at {@code sea-level: 63} with one metre per
     * block is a legitimate configuration, and there vanilla's stages are exactly as correct as they
     * are in a vanilla world. It is the mixture that is broken, and the mixture is invisible from
     * in-game until somebody digs into a mountain and finds it flooded.
     *
     * <p>Built with {@link String#format} on purpose. {@link System.Logger} formats its varargs with
     * {@link java.text.MessageFormat}, where an apostrophe opens a quoted section and silently
     * swallows every {@code {n}} placeholder after it -- and this text is full of possessives.
     */
    static String vanillaFrameWarning(VerticalScale scale, Features features) {
        if (!features.vanillaCaves() && !features.vanillaDecorations()) {
            return null;
        }
        if (scale.seaLevel() == VANILLA_SEA_LEVEL && scale.minY() == VANILLA_MIN_Y
                && scale.metersPerBlock() == VANILLA_METERS_PER_BLOCK) {
            return null;
        }
        return String.format(java.util.Locale.ROOT,
                "[TerraForge-Generator] generation.vanilla-caves/vanilla-decorations are enabled, but "
                        + "this world's vertical frame (sea level %d, min y %d, %s m per block) is not "
                        + "vanilla's (%d/%d/%s). Vanilla's carvers, aquifer and decorators read "
                        + "vanilla's frame, not this one: expect caves that remove %d m of real rock "
                        + "each, water flooding carved rock up to y=%d, and lava at altitude. Turn them "
                        + "off, or run this world in vanilla's frame.",
                scale.seaLevel(), scale.minY(), scale.metersPerBlock(),
                VANILLA_SEA_LEVEL, VANILLA_MIN_Y, VANILLA_METERS_PER_BLOCK,
                Math.round(VANILLA_CAVE_BLOCKS * scale.metersPerBlock()), VANILLA_SEA_LEVEL);
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunk) {
        if (beyondEdge(chunkX, chunkZ)) {
            // Same reason as below: whatever was prefilled, past the edge of the planet is void.
            chunk.setRegion(0, chunk.getMinHeight(), 0, 16, chunk.getMaxHeight(), 16, Material.AIR);
            return;
        }
        ChunkSampler.ChunkSamples samples = pipeline.sampleChunk(chunkX, chunkZ);
        int base = base(chunk);
        int floor = base + bedrockThickness;
        int ceiling = top(chunk) - 1;

        // Paper promises an empty ChunkData when shouldGenerateNoise() is false. Leaf's generation
        // pipeline can still hand us prefilled vanilla noise for a newly-created Multiverse world;
        // clear it explicitly so DEM columns always replace, never merge with, vanilla terrain.
        chunk.setRegion(0, chunk.getMinHeight(), 0, 16, chunk.getMaxHeight(), 16, Material.AIR);

        if (FIRST_DEM_CHUNK_LOGGED.compareAndSet(false, true)) {
            TerrainSample centre = samples.at(8, 8);
            LOG.log(System.Logger.Level.INFO, "[TerraForge-Generator] DEM terrain active at chunk {0},{1}: "
                    + "centre={2} m, surface Y={3}", chunkX, chunkZ,
                    centre.elevationMeters(), centre.surfaceY());
        }

        for (int localZ = 0; localZ < ChunkSampler.ChunkSamples.SIZE; localZ++) {
            for (int localX = 0; localX < ChunkSampler.ChunkSamples.SIZE; localX++) {
                TerrainSample sample = samples.at(localX, localZ);
                int surfaceY = Math.clamp(sample.surfaceY(), floor, ceiling);

                chunk.setRegion(localX, base, localZ,
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
        if (beyondEdge(chunkX, chunkZ)) {
            return;
        }
        ChunkSampler.ChunkSamples samples = pipeline.sampleChunk(chunkX, chunkZ);
        int floor = base(chunk) + bedrockThickness;
        int ceiling = top(chunk) - 1;

        for (int localZ = 0; localZ < ChunkSampler.ChunkSamples.SIZE; localZ++) {
            for (int localX = 0; localX < ChunkSampler.ChunkSamples.SIZE; localX++) {
                TerrainSample sample = samples.at(localX, localZ);
                int surfaceY = Math.clamp(sample.surfaceY(), floor, ceiling);
                SurfacePalette.Layers layers = SurfacePalette.forColumn(sample.biome(),
                        (chunkX << 4) + localX, (chunkZ << 4) + localZ);

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
        if (beyondEdge(chunkX, chunkZ)) {
            return;
        }
        int base = base(chunk);
        chunk.setRegion(0, base, 0, 16, base + bedrockThickness, 16, Material.BEDROCK);
    }

    /**
     * Lowest Y of the terrain: the world floor, or {@code terrain.generated-min-y} when the terrain is
     * kept to a band. Everything below it stays air -- behind the bedrock, where no player reaches.
     */
    private int base(ChunkData chunk) {
        return Math.max(chunk.getMinHeight(), verticalScale.minY());
    }

    /** One above the highest Y the terrain may reach. */
    private int top(ChunkData chunk) {
        return Math.min(chunk.getMaxHeight(), verticalScale.maxY());
    }

    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        if (beyondEdge(x >> 4, z >> 4)) {
            return worldInfo.getMinHeight();
        }
        ChunkSampler.ChunkSamples samples = pipeline.sampleChunk(x >> 4, z >> 4);
        TerrainSample sample = samples.at(x & 15, z & 15);
        int surface = sample.surfaceY();
        return sample.isWater() ? Math.max(surface, sample.waterSurfaceY()) : surface;
    }

    /** Avoids Paper's random safe-spawn scan: the origin surface is deterministic terrain. */
    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        int y = Math.clamp(getBaseHeight(world, random, 0, 0, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1,
                world.getMinHeight() + 1, world.getMaxHeight() - 1);
        return new Location(world, 0.5, y, 0.5);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new TerraForgeBiomeProvider(pipeline, biomeMapper);
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false; // the terrain comes from the DEM, not from vanilla's noise router
    }

    /** Leaf may select this per-chunk overload instead of the legacy no-argument hook. */
    @Override
    public boolean shouldGenerateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false; // surface materials come from real land cover, see SurfacePalette
    }

    /** Prevents Leaf from applying a vanilla surface pass over DEM terrain. */
    @Override
    public boolean shouldGenerateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    @SuppressWarnings("deprecation") // the flag is deprecated upstream; the hook it guards is not
    public boolean shouldGenerateBedrock() {
        return false; // written by generateBedrock, at the configured thickness
    }

    /**
     * Vanilla's carvers, canyons and aquifer -- <em>not</em> TerraForge's caves.
     *
     * <p>Paper calls {@link #generateCaves} unconditionally and consults this flag only to decide
     * whether to <em>also</em> run {@code delegate.applyCarvers}. Returning
     * {@code generation.caves} here therefore switched on vanilla's carvers while leaving
     * TerraForge's own carver running regardless: the documented meaning of the flag and its actual
     * effect were unrelated. This is the vanilla switch; {@code generation.caves} reaches
     * {@link KarstCaveCarver} and stops there.
     */
    @Override
    public boolean shouldGenerateCaves() {
        return features.vanillaCaves();
    }

    /** Leaf may select this per-chunk overload instead of the legacy no-argument hook. */
    @Override
    public boolean shouldGenerateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return features.vanillaCaves();
    }

    /**
     * TerraForge's underground -- stone pockets, cave systems and ore veins -- then its karst caves.
     * Geographic and block coordinates only; the supplied vanilla random is ignored.
     *
     * <p>The last stage that writes {@code ChunkData}, so the stone laid by {@link #generateNoise} and
     * the soil laid by {@link #generateSurface} are both final here, and the underground model built
     * from the samples matches the chunk block for block.
     */
    @Override
    public void generateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunk) {
        if (beyondEdge(chunkX, chunkZ)) {
            return;
        }
        if (underground != null) {
            java.util.Map<Material, BlockData> palette = palette();
            underground.generate(pipeline.sampleChunk(chunkX, chunkZ),
                    base(chunk) + bedrockThickness, top(chunk) - 1,
                    (x, y, z, material) -> chunk.setBlock(x, y, z, palette.get(material)));
        }
        if (caveCarver != null) {
            caveCarver.carve(chunkX, chunkZ, chunk, base(chunk));
        }
    }

    /**
     * {@code ChunkData.setBlock(Material)} creates a fresh block state for every call; thousands of
     * blocks a chunk make that the pass's largest cost. One immutable state per material is shared
     * instead. Built lazily because block states need a running server; a benign race builds it twice.
     */
    private java.util.Map<Material, BlockData> palette() {
        java.util.Map<Material, BlockData> palette = undergroundPalette;
        if (palette == null) {
            java.util.Map<Material, BlockData> built = new java.util.EnumMap<>(Material.class);
            built.put(Material.CAVE_AIR, Material.CAVE_AIR.createBlockData());
            for (dev.terraforge.generator.underground.Mineral mineral
                    : dev.terraforge.generator.underground.Mineral.values()) {
                built.put(mineral.material(), mineral.material().createBlockData());
            }
            palette = java.util.Collections.unmodifiableMap(built);
            undergroundPalette = palette;
        }
        return palette;
    }

    /**
     * Vanilla's decoration pass, all of it or none of it.
     *
     * <p>Paper exposes one boolean for {@code applyBiomeDecoration}, so trees, grass and flowers are
     * inseparable from ore veins, {@code spring_water}, {@code spring_lava}, {@code lake_lava}, kelp
     * and seagrass. Splitting them needs a biome datapack that edits every feature list, not a flag
     * here -- so the flag is named for what it does.
     */
    @Override
    public boolean shouldGenerateDecorations() {
        return features.vanillaDecorations();
    }

    /**
     * TerraForge's own vegetation, placed from real land cover in this world's vertical frame.
     *
     * <p>A populator and not vanilla's decoration pass, so the forests come without the ore veins,
     * lava lakes and springs that {@code vanilla-decorations} would bring into a scaled world.
     */
    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        if (!features.vegetation()) {
            return List.of();
        }
        BlockPopulator vegetation = new VegetationPopulator(pipeline,
                new VegetationPlan(features.vegetationDensity(), features.customTrees(), features.farmland(),
                        features.farmlandShare()));
        if (extent == null) {
            return List.of(vegetation);
        }
        return List.of(new BlockPopulator() {
            @Override
            public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ,
                                 LimitedRegion region) {
                if (!beyondEdge(chunkX, chunkZ)) {
                    vegetation.populate(worldInfo, random, chunkX, chunkZ, region);
                }
            }
        });
    }

    /** The planet's edge, or {@code null} when this world has no border. */
    public WorldExtent extent() {
        return extent;
    }

    private boolean beyondEdge(int chunkX, int chunkZ) {
        return extent != null && !extent.intersectsChunk(chunkX, chunkZ);
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

    @Override
    public boolean shouldGenerateStructures(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return false;
    }

    /** Sea level in blocks, as the vertical scale defines it. */
    public int seaLevel() {
        return verticalScale.seaLevel();
    }

    /**
     * Which generation stages this world runs.
     *
     * <p>Three switches, because they answer three different questions, and conflating them is what
     * made {@code generation.caves} enable vanilla's carvers instead of TerraForge's.
     *
     * @param karstCaves         run {@link KarstCaveCarver} ({@code generation.caves})
     * @param vanillaCaves       also run vanilla's carvers and aquifer ({@code generation.vanilla-caves})
     * @param vanillaDecorations run vanilla's whole decoration pass ({@code generation.vanilla-decorations})
     * @param vegetation         run TerraForge's own vegetation populator ({@code generation.vegetation.enabled})
     * @param vegetationDensity  multiplier on its placement probabilities ({@code generation.vegetation.density})
     * @param customTrees        draw TerraForge's procedural trees ({@code generation.vegetation.custom-trees})
     * @param farmland           till flat cropland into fields ({@code generation.vegetation.farmland})
     * @param underground        ores, stone pockets and cave systems ({@code generation.underground})
     * @param farmlandShare      share of flat cropland tilled ({@code generation.vegetation.farmland-share})
     */
    public record Features(boolean karstCaves, boolean vanillaCaves, boolean vanillaDecorations,
                           boolean vegetation, double vegetationDensity, boolean customTrees, boolean farmland,
                           dev.terraforge.core.config.TerraForgeConfig.UndergroundSection underground,
                           double farmlandShare) {

        public Features {
            if (underground == null) {
                underground = dev.terraforge.core.config.TerraForgeConfig.UndergroundSection.off();
            }
        }

        /** No underground pass: stone all the way down; every flat cropland field tilled. */
        public Features(boolean karstCaves, boolean vanillaCaves, boolean vanillaDecorations,
                        boolean vegetation, double vegetationDensity, boolean customTrees, boolean farmland) {
            this(karstCaves, vanillaCaves, vanillaDecorations, vegetation, vegetationDensity, customTrees,
                    farmland, null, 1.0);
        }

        /** The three vanilla-frame switches only; no TerraForge vegetation or underground. */
        public Features(boolean karstCaves, boolean vanillaCaves, boolean vanillaDecorations) {
            this(karstCaves, vanillaCaves, vanillaDecorations, false, 1.0, false, false, null, 1.0);
        }

        public static Features from(dev.terraforge.core.config.TerraForgeConfig.GenerationSection generation) {
            var vegetation = generation.vegetation();
            return new Features(generation.caves(), generation.vanillaCaves(),
                    generation.vanillaDecorations(), vegetation.enabled(), vegetation.density(),
                    vegetation.customTrees(), vegetation.farmland(), generation.underground(),
                    vegetation.farmlandShare());
        }
    }
}
