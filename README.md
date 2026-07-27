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

**Version 0.1.0** — the first tagged release. A world generated now has the real relief, coastlines,
water and land cover of the Earth, with countries, regions and place names behind the commands, town
geography for Towny and geographic markers on BlueMap. Missing data coverage always falls back
conservatively rather than failing.

While the major version is `0`, a minor bump may change the world or prepared-data format; each
release says so explicitly. See [CHANGELOG.md](CHANGELOG.md).

| Phase | Scope | State |
|---|---|---|
| 1 | Gradle multi-module project | done |
| 2 | Earth coordinate system | done — `GeoPoint`, `EarthLocation`, `CoordinateTransformer` |
| 3 | Projections | done — Web Mercator, equirectangular, registry |
| 4 | DEM provider | done — `.tfdem` writer/reader, memory-mapped tiles, `DemElevationProvider`; source input is SRTM HGT or north-up WGS84 GeoTIFF |
| 5 | Terrain generator | done — `DefaultTerrainPipeline`, `CachingChunkSampler`, Paper `ChunkGenerator`, surface palette |
| 6 | Water / biome system | in progress — prepared WKB water polygons and `.tflc` land-cover grids are loaded at runtime; broader source-format support remains |
| 7 | Geographic database | done — prepared SQLite countries, regions and gazetteer cities, loaded read-only at startup |
| 8 | Country / region detection | done — `SqliteBoundaryIndex` over a JTS `STRtree` with exact point-in-polygon tests |
| 9 | Commands | done — `/earth` info, whereami, coords, distance, country, city, teleport, cache, pregenerate, towny, debug (+ live overlay), reload, with argument tab completion |
| 10 | Caching | done — `CacheManager`, byte-bounded tile cache, `CacheStatistics` |
| 13 | BlueMap | done — `GeoMarkerService` registry published as TerraForge-owned marker sets, re-published on BlueMap reload |
| 14 | CLI | done — `info`, `prepare-dem`, `prepare-boundaries`, `prepare-cities`, `prepare-landcover`, `prepare-geo`, `prepare-region`, `validate`, `pregenerate` |
| 11 | Pregeneration | done — in-game job, one chunk per tick, plus an offline planner with a DEM coverage check |
| 12 | Towny / NewTowny | done — geography on creation, spawn move, rename and deletion; `/earth towny refresh` backfill; SQLite writes off the server thread |
| 15 | Tests | done — unit and integration tests per module; `./gradlew build` runs them all |
| 16 | Benchmarks | done — `CoordinateBenchmark` for the per-column maths, `GeographyBenchmark` for server-thread lookups on an oversized dataset |
| 17 | Documentation | in progress — every shipped feature is documented; the docs grow with the roadmap |

Every CLI subcommand is implemented. `terraforge validate` checks a prepared database for the
problems that survive import — overlapping boundaries, duplicate ISO codes, missing or misplaced
capitals — and prints a coverage report.

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
| [CHANGELOG.md](CHANGELOG.md) | what changed in each release |
| [DATA_SOURCES.md](DATA_SOURCES.md) | licences and attribution |

---

## Licensing

TerraForge ships **no geodata**. Every dataset is downloaded and prepared by the server operator,
under its own licence. See [DATA_SOURCES.md](DATA_SOURCES.md) for sources, licences and required
attribution.
