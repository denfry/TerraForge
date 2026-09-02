# Architecture

## The one rule

**Minecraft never learns about GIS.**

```
Minecraft layer      ChunkGenerator · Commands · Events · Players
                                   ↓
TerraForge core      EarthService · GeoService · TerrainService · config · caches
                                   ↓
GIS layer            Projection · DEM tiles · polygons · spatial index · SQLite
```

Dependencies point inwards only. `terraforge-core` and `terraforge-geo` contain no Paper, Towny or
BlueMap types and compile against a plain JDK. Replacing Paper, or replacing the data source, is a
change at one edge — never a rewrite of the pipeline.

## Modules

| Module | Depends on | May import |
|---|---|---|
| `terraforge-core` | — | JTS, Jackson, Caffeine |
| `terraforge-geo` | core | + SQLite, HikariCP |
| `terraforge-generator` | core, geo | + Paper API (`compileOnly`) |
| `terraforge-towny` | core | + Paper, Towny (`compileOnly`) |
| `terraforge-bluemap` | core | + Paper, BlueMap API (`compileOnly`) |
| `terraforge-plugin` | all of the above | + Paper |
| `terraforge-cli` | core, geo | + picocli, TwelveMonkeys ImageIO |
| `terraforge-benchmark` | core, geo, generator | + JMH |

`terraforge-plugin` exists so the Paper bootstrap is not mixed into the terrain pipeline; the spec's
six-module list is otherwise preserved. `terraforge-benchmark` keeps JMH out of the shipped jar.

Third-party libraries are shaded **and relocated** into `dev.terraforge.libs.*`, so TerraForge cannot
clash with another plugin's copy of Jackson or HikariCP.

## Layers in detail

### Core — `terraforge-core`

- `coord` — `GeoPoint`, `GeoBounds`, `EarthLocation`, `MinecraftPos`, `CoordinateTransformer`
- `projection` — `Projection` SPI, Web Mercator, equirectangular, `ProjectionRegistry`
- `geodesy` — Haversine and Vincenty distances, bearings
- `terrain` — `ClimateBiome`, `TerrainSample`, `VerticalScale`
- `data` — provider SPIs: elevation, water, land cover, country, biome
- `api` — `EarthService`, `GeoService`, `TerrainService`, `GeoMarkerService`
- `config` — typed `terraforge.yml` model and strict validation
- `cache` — `CacheManager`, `ManagedCache`, `CacheStatistics`

Two provider SPIs changed shape, because the generator asks about a *block* and used to ask badly.
`terraforge-core` publishes to mavenLocal, so a third-party provider has to follow:

| SPI | Method | Why |
|---|---|---|
| `ElevationProvider` | `averageElevationAt(lat, lon, latSpan, lonSpan)` beside `elevationAt(lat, lon)` | a block covers a footprint, not a point; the default implementation is still a point sample |
| `WaterProvider` | `waterColumnAt(lat, lon, knownElevationMeters)` → `WaterColumn(type, surfaceElevationMeters, bedDepthMeters)` | replaces `waterSurfaceElevation` and `riverBedDepthMeters` |

A column's water is one fact, so it is one query. Classification, surface elevation and bed depth
used to be three calls — three spatial-index lookups and three geometry `covers` tests per column —
and, worse, three answers that could contradict each other: the bounded geometry cache evicting
between two of them let a column be classified as a lake and then told it had no lake surface.
`WaterColumn.surfaceElevationMeters` is `NO_DATA` when the source ships no absolute level, and a
caller must read that as "this water cannot be placed", never as sea level.

### Geo — `terraforge-geo`

- `dem` — `.tfdem` tiles: `DemTileKey`, `DemTile`, `DemReader`, `TfDemFormat`
- `index` — `SpatialIndex`: JTS STRtree + prepared geometries
- database access to `terraforge.db` (schema in `src/main/resources/schema.sql`)

### Generator — `terraforge-generator`

- `pipeline` — `ChunkSampler`, `TerrainPipeline`
- `biome` — `BiomeMapper`: the single place `ClimateBiome` meets `org.bukkit.block.Biome`
- the Paper `ChunkGenerator` adapter

## Generation pipeline

```
Minecraft chunk (x, z)
  → CoordinateTransformer.chunkBounds        geographic bounds, and one block's footprint in degrees
  → ElevationProvider.averageElevationAt     DEM tiles under that footprint, averaged
  → WaterProvider.waterColumnAt              type + surface elevation + bed depth, one lookup
  → BiomeProvider                            climate biome from land cover + elevation + latitude
  → VerticalScale                            elevation (m) → Minecraft Y
  → terrain shaping                          stone / surface / water columns
  → KarstCaveCarver                          only when `generation.caves` is on
  → chunk output
```

Sampling is **per column, not per block**: 256 samples for a chunk, cached as one
`ChunkSampler.ChunkSamples` object. The column's elevation is the mean of the DEM samples that
block's geographic footprint covers, not the sample under its centre — see [dem.md](dem.md#sampling).

**There is no vegetation stage, and there never was one.** TerraForge grows nothing: trees, grass
and flowers only ever arrived from vanilla's own decoration pass, which the generator now exposes
as the explicit `generation.vanilla-decorations` switch and leaves off by default. The same is true
of `generation.vanilla-caves`. Those stages read vanilla's vertical frame (`min_y -64`,
`sea_level 63`, one metre per block) rather than this world's `terrain.*`, so in a scaled world they
edit the terrain by hundreds of metres at a time; the generator logs a warning when either switch is
on and the frame is not vanilla's. `generation.caves` drives TerraForge's own `KarstCaveCarver` and
nothing else.

Determinism is a contract: the same geographic point, dataset and configuration always produce the
same terrain. Noise, where used, is seeded from the geographic position — never from wall time or a
chunk-load counter.

## What must never happen

These are the costs the design exists to avoid:

- an HTTP request per chunk
- reading a full GeoTIFF at runtime
- a SQL query per block
- scanning every country polygon per point
- heavy GIS work on the main server thread
- an unbounded cache

Every provider is fed by data prepared offline by the CLI; the server only memory-maps tiles and
queries an in-memory R-tree.

## Data flow at startup

```
terraforge.yml → ConfigLoader (validate, fail fast)
               → ProjectionRegistry → Projection
               → CoordinateTransformer, VerticalScale
               → providers over prepared data in plugins/TerraForge/data
               → CacheManager registers every cache
               → integrations detected (Towny, BlueMap) — absence is normal, never fatal
```
