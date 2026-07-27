# TerraForge — Real Earth Engine for Minecraft

TerraForge generates a Minecraft world from the **real terrain of the Earth** — real continents,
real coastlines, real oceans and lakes, real mountain ranges, real biomes.

And nothing else.

> Germany gets the real relief of Germany. It does **not** get a pre-generated Berlin, Frankfurt,
> autobahns, railways or buildings. Cities exist only because players build them, and Towny manages
> them.

```
REAL EARTH TERRAIN → TerraForge → natural Minecraft terrain → players build → Towny governs → BlueMap displays
```

---

## What it does

| Generated from real data | Never generated |
|---|---|
| Continents, coastlines, islands | Roads, highways, streets |
| Ocean depth, lakes, natural rivers | Buildings, villages, structures |
| Mountains, valleys, plains, deserts | Railways, bridges, airports |
| Biomes from real land cover | Power lines, industrial areas |
| Natural vegetation, snow, ice | Anything man-made, at all |

`generation.natural-only: true` is the default, and the `infrastructure.*` switches must all stay
`false` — the plugin refuses to start otherwise. There is no code path in the generator that places
a man-made structure.

---

## Status

This repository is at **Phase 4 of the roadmap**: the coordinate system and the DEM layer are in
place and tested — real elevation can be prepared offline and sampled at runtime. Terrain generation
itself is being built on top of them.

| Phase | Scope | State |
|---|---|---|
| 1 | Gradle multi-module project | done |
| 2 | Earth coordinate system | done — `GeoPoint`, `EarthLocation`, `CoordinateTransformer` |
| 3 | Projections | done — Web Mercator, equirectangular, registry |
| 4 | DEM provider | done — `.tfdem` writer/reader, memory-mapped tiles, `DemElevationProvider`; source input is SRTM HGT (GeoTIFF via GDAL for now) |
| 5 | Terrain generator | contracts defined (`TerrainPipeline`, `ChunkSampler`) |
| 6 | Water / biome system | contracts defined (`WaterProvider`, `BiomeProvider`, `ClimateBiome`) |
| 7 | Geographic database | schema written (`schema.sql`) |
| 8 | Country / region detection | contracts defined (`GeoService`, `SpatialIndex`) |
| 9 | Commands | permissions and command surface declared in `plugin.yml` |
| 10 | Caching | done — `CacheManager`, byte-bounded tile cache, `CacheStatistics` |
| 11–17 | Pregeneration, Towny, BlueMap, CLI, tests, benchmarks, docs | in progress |

CLI subcommands that are not implemented yet say so explicitly and write nothing.

---

## Modules

```
TerraForge
├── terraforge-core        pure Java: coordinates, projection, config, service API   (no Paper)
├── terraforge-geo         GIS: DEM tiles, spatial index, SQLite database            (no Paper)
├── terraforge-generator   terrain pipeline + Paper ChunkGenerator
├── terraforge-towny       optional Towny bridge         (soft dependency)
├── terraforge-bluemap     optional BlueMap bridge       (soft dependency)
├── terraforge-plugin      Paper entry point, commands, wiring → the shipped jar
├── terraforge-cli         offline DEM/geodata preparation
└── terraforge-benchmark   JMH harness
```

**The architectural rule: Minecraft never learns about GIS.** Paper types appear only in
`terraforge-generator`, `terraforge-plugin` and the two integration modules. The core and geo
modules would compile unchanged against a different game engine.

Note: the spec lists six modules; `terraforge-plugin` was split out of `terraforge-generator` so the
Paper bootstrap (commands, events, service registration) stays separate from the terrain pipeline,
and `terraforge-benchmark` was added to keep JMH out of the shipped jar.

---

## Build

```bash
./gradlew build              # everything, incl. tests
./gradlew test               # tests only
./gradlew :terraforge-plugin:shadowJar   # plugin jar
./gradlew :terraforge-cli:shadowJar      # CLI jar
./gradlew :terraforge-benchmark:jmh      # benchmarks
```

Artifacts:

- `terraforge-plugin/build/libs/TerraForge-<version>.jar` → drop into `plugins/`
- `terraforge-cli/build/libs/terraforge-cli-<version>.jar` → run with `java -jar`

Requires JDK 21. Target platform versions live in `gradle.properties` and nowhere else.

---

## Quick start (central-europe test region)

The project is developed against a bounded region first — Germany and its neighbours — and only then
scaled to the planet. See `docs/installation.md` for the full walkthrough.

```bash
# 1. prepare data offline (source datasets: see DATA_SOURCES.md)
java -jar terraforge-cli.jar prepare-region \
    --lat-min 47.0 --lat-max 55.5 --lon-min 5.0 --lon-max 15.5 \
    -i ./source-data -o ./server/plugins/TerraForge

# 2. inspect what the world will look like
java -jar terraforge-cli.jar info -c ./server/plugins/TerraForge/terraforge.yml

# 3. start the server, then in-game
/earth whereami
```

---

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | module layout, layering rules, data flow |
| [docs/installation.md](docs/installation.md) | server setup and world creation |
| [docs/configuration.md](docs/configuration.md) | every option in `terraforge.yml` |
| [docs/data-sources.md](docs/data-sources.md) | which datasets to use and why |
| [docs/dem.md](docs/dem.md) | DEM preparation and the `.tfdem` format |
| [docs/projection.md](docs/projection.md) | projections, scale, distortion |
| [docs/towny.md](docs/towny.md) | Towny integration |
| [docs/bluemap.md](docs/bluemap.md) | BlueMap integration |
| [docs/performance.md](docs/performance.md) | budgets, caching, benchmarks |
| [docs/pregeneration.md](docs/pregeneration.md) | preparing chunks ahead of players |
| [docs/development.md](docs/development.md) | working on TerraForge |
| [docs/troubleshooting.md](docs/troubleshooting.md) | common failures |
| [DATA_SOURCES.md](DATA_SOURCES.md) | licences and attribution |

---

## Licensing

TerraForge ships **no geodata**. Every dataset is downloaded and prepared by the server operator,
under its own licence. See [DATA_SOURCES.md](DATA_SOURCES.md) for sources, licences and required
attribution.
