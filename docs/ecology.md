# Ecology and living environment (planned, 2.x)

Status: approved architecture for the 2.x line; not implemented. Ecology turns the terrain model
into a geographically grounded living environment. At a coordinate, TerraForge resolves local
climate, season, weather, plant community and eligible real species before a Minecraft adapter
places a block or permits a spawn.

The goal is an offline Earth that feels alive without pretending to know the exact position of
every organism. A species must occur in the region and pass habitat, elevation, climate, water and
season checks. The same world seed, geography, data-pack version and game day always produce the
same result.

Physical layers that ecology consumes are specified in
[natural-systems.md](natural-systems.md).

## Scope and boundaries

| TerraForge owns | TerraForge does not own |
|---|---|
| Species occurrence, habitat suitability, seasonal state, natural flora placement, weather selection and spawn admission | Entity AI, pathfinding, combat, breeding, anatomy simulation, assets or resource-pack delivery |
| Offline preparation, validation, attribution and versioning of ecology data | HTTP, GeoTIFF/Shapefile parsing or SQL queries in chunk and spawn hot paths |
| Bounded species representations through vanilla entities or optional models | A permanent copy of all organisms, food-chain simulation or per-entity migration state |

Vanilla/Paper owns entity behaviour. ModelEngine, when installed, supplies only an appearance and
animation after Paper creates the entity. This preserves [architecture.md](architecture.md):
Minecraft never learns about GIS.

### Non-goals

- Historical weather replay or online forecast services.
- Claiming a surveyed, exact tree or animal at a one-block position.
- Farms, fences, roads, buildings, zoos, hunting stands or other man-made features.
- Replacing Paper's mob cap, safety checks or entity lifecycle.
- Bundling third-party models, textures, sounds or resource packs into the plugin jar.

## Environmental model

`EcologySample` is the immutable answer for one geographic coordinate and game day. It is pure
data: it cannot access Bukkit, place a block or spawn an entity.

```text
coordinate + game day
  -> ClimateProvider          monthly normals and terrain correction
  -> WeatherSimulator         deterministic local weather
  -> EnvironmentResolver      land cover, elevation, water and snow
  -> EcologyProvider          community and species suitability
  -> EcologySample
  -> generator / Paper adapters
```

`EcologySample` contains:

- `ClimateSample`: interpolated minimum/maximum temperature, precipitation, humidity, cloud
  fraction, wind, snow probability and growing-season signal.
- `WeatherState`: clear, cloudy, rain, thunderstorm, snow, blizzard, fog, heat or drought with a
  deterministic start, end and intensity.
- `EnvironmentSample`: land-cover class, elevation, slope band, water proximity/type, surface
  moisture and snow cover.
- `SeasonPhase`: dormant, emergence, growth, flowering, fruiting, migration or overwintering.
- `EcologicalCommunity`: a pack-defined community such as temperate mixed forest, alpine meadow,
  Sahel savanna or tropical mangrove coast.
- Ordered `SpeciesCandidate` values, with a machine-readable admission or rejection reason.

An ecological range only makes a species possible. The resolver also requires compatible habitat,
elevation, climate, season and local capacity. A tiger range never permits a tiger on a glacier;
a tropical range does not permit mangroves on an inland dry slope.

## Offline climate and weather

The initial baseline is [CHELSA v2.1](https://www.chelsa-climate.org/datasets/chelsa_climatologies),
a global kilometre-scale climate dataset with monthly temperature, precipitation, humidity, cloud
cover and related variables. The CLI prepares only selected coverage as compact versioned tiles and
does not redistribute raw data without checking the source terms.

Each climate tile stores twelve monthly normals for runtime variables. The provider interpolates
adjacent months by game day and uses hemisphere-aware seasons. Elevation and existing land/water
data refine the result where a pack defines a correction. Outside coverage it returns `NO_DATA`; it
never invents a climate.

`WeatherSimulator` adds short weather fronts. Their seed is:

```text
world seed + ecology-pack identity + synoptic-cell key + game-week
```

Columns in one synoptic cell therefore share an event, while restarts reproduce it. The simulator
chooses only locally possible states: snow requires cold precipitation, drought needs an arid
seasonal signal, thunderstorms require warm wet conditions. It never reads wall time or calls an
API.

Paper weather is global per world; ecology is local. 2.x exposes both honest modes:

| Mode | Behaviour | Default |
|---|---|---|
| `dominant-region` | Uses the synoptic cell containing the largest group of online players to set Paper's global rain/thunder state. | yes |
| `local-effects` | Leaves Paper's global weather alone and exposes local state to supported client/resource-pack integrations. | no |

`local-effects` never claims to render local rain without a client capability. It can still control
TerraForge snow cover, spawn conditions and diagnostics locally.

## Real species and communities

A stable ID, for example `terraforge:panthera_tigris` or `terraforge:adansonia_digitata`, identifies
each species. Its definition records scientific and display names, taxonomic group, provenance,
representation and ecology profile:

```yaml
id: terraforge:panthera_tigris
scientific-name: Panthera tigris
kind: fauna
range-layer: fauna/tiger.tfrange
habitat:
  land-cover: [TREE_COVER, SHRUBLAND, HERBACEOUS_WETLAND]
  elevation-m: [0, 4200]
  temperature-c: [-20, 38]
  water-distance-m: [0, 12000]
  seasons: [growth, flowering, fruiting, migration]
population:
  density-per-100km2: 0.02
  group-size: [1, 2]
  active-hours: [dusk, night]
representation:
  mode: model
  vanilla-entity: CAT
  model-id: terraforge:tiger
```

This shows the rule shape, not a promise that this species/model ships in 2.0. No species becomes
eligible without a validated range layer and an explicit representation.

Communities keep the world legible at Minecraft scale. They define dominant structural plants,
ground cover, seasonal rules, density and a small weighted candidate list. The resolver selects an
individual real species only after every hard constraint passes.

### Data policy

The manifest identifies every source, version, licence, checksum and required citation. GBIF
occurrences may prepare or validate a pack, but raw points are not range polygons. GBIF downloads
need a user account and carry a DOI/citation; packs retain this provenance instead of distributing
an undocumented scrape. See [GBIF downloads](https://techdocs.gbif.org/en/data-use/api-downloads)
and [GBIF citation guidance](https://www.gbif.org/citation-guidelines).

Curated range layers with redistribution permission take priority over occurrence-derived estimates.
The CLI rejects missing licences/citations. Sensitive species use allowed generalisation, never exact
public occurrence points.

## Pack format and validation

An `ecology-pack` lives below `plugins/TerraForge/data/ecology/` or a configured read-only path:

```text
ecology-pack/
  manifest.json
  climate/<tile>.tfclimate
  ranges/<tile>.tfrange
  communities.json
  species/*.json
  attribution/NOTICE.md
  attribution/sources.json
```

`manifest.json` declares format, pack ID/version, geographic coverage, climate baseline, all sources
and SHA-256 checksums. The runtime validates path containment, declared sizes, checksums, duplicate
species IDs, coordinate ranges, licences and model IDs before publishing a provider. A bad ecology
pack disables ecology only; terrain continues with the [documented data fallbacks](data-sources.md).

The CLI owns `fetch-ecology`, `prepare-ecology` and `validate-ecology`. Network access is allowed
only in explicit fetching. Preparation rasterises and indexes ranges, samples climate to world scale,
builds the attribution report and fails for missing provenance, unsupported licences or bad geometry.

## Flora generation

Flora is written once while a chunk generates, not maintained by an always-running loop. The
generator receives `EcologySample` beside `TerrainSample`; a platform-neutral resolver creates a
placement plan and the Paper adapter writes blocks.

| Layer | Examples | Rule |
|---|---|---|
| Ground cover | grass, moss, flowers, reeds, algae, snow | Requires matching surface, moisture, light and season. |
| Structural vegetation | trees, cacti, mangroves, bamboo, vines, tall ferns | Requires support, slope, water relation, vertical space and community density. |
| Signature plants | baobab, giant sequoia, dragon tree | Rare, explicitly modelled species; never substituted with an unrelated tree. |

A stable hash of geographic position, species ID and pack version selects each candidate position.
Regenerating an untouched chunk reproduces flora. Existing chunks are never rewritten merely because
the season changes; 2.x alters only new chunks and optional safe transient overlays. A missing model
falls back to a configured compatible block profile or produces no placement.

## Fauna and controlled spawning

`EcologySpawnController` runs on a bounded schedule only for loaded chunks near players. It never
scans unloaded chunks, walks all world entities or creates a permanent entity for every record.

```text
loaded chunk near player
  -> geographic coordinate + EcologySample
  -> eligible real species
  -> season, hour, weather, surface and clearance checks
  -> per-species / per-area cooldown and capacity checks
  -> Paper spawn attempt
  -> optional ModelEngine appearance attachment
```

Paper keeps final authority: mob cap, spawn-event cancellation, collision, light, distance and
server safety checks still apply. The controller caps attempts per tick and enforces per-chunk
cooldown, synoptic-area capacity and group bounds. Killing/unloading an entity cannot force rare
spawns. A TerraForge-controlled entity stores `species-id`, pack version and controller marker in
`PersistentDataContainer`; named, tamed, bred, ridden or player-associated entities never despawn
through TerraForge. Species without a faithful vanilla or installed model representation do not
spawn; they remain catalogue entries.

## Modules and interfaces

| Module | Responsibility | Dependencies |
|---|---|---|
| `terraforge-core` | Records/SPIs: `ClimateProvider`, `EcologyProvider`, `SpeciesRangeProvider`, `SpeciesCatalog`, `ClimateSample`, `EcologySample` | core only |
| `terraforge-geo` | Bounded tile readers, range index and cache implementations | core |
| `terraforge-ecology` | Pure season, weather, habitat and candidate rules | core |
| `terraforge-cli` | Explicit download, preparation, validation and attribution report | core, geo, ecology |
| `terraforge-generator` | Turns deterministic flora plans into chunk blocks | core, ecology, Paper `compileOnly` |
| `terraforge-plugin` | Weather coordination, spawn controller, commands and diagnostics | core, geo, ecology, generator, Paper |
| `terraforge-modelengine` | Optional appearance attachment after a Paper entity exists | core, ecology, Paper/ModelEngine `compileOnly` |

`terraforge-geo` owns all tile caches. Each implements `ManagedCache`, registers with
`CacheManager`, has a configured bound and reports statistics. Providers remain thread-safe,
non-blocking and return no data outside coverage.

## External assets and licences

TerraForge code/data packs include no third-party creative assets. Resource packs are separate
downloads with a manifest per imported asset:

```yaml
source: https://example.invalid/project
source-version: 1.2.3
source-sha256: <hash>
author: Example author
license: MIT
files: [assets/example/textures/entity/example.png]
derivative-license: MIT
```

The project publishes separate packs where licences require it:

- `terraforge-open-assets`: permissively reusable or original assets only.
- `terraforge-alexs-mobs-compat`: a separate GPL-3.0 distribution when using assets from a
  GPL-licensed Alex's Mobs version.
- `terraforge-community-licensed`: assets covered by explicit written author permission.

No-derivatives, all-rights-reserved and assets excluded from an otherwise open code licence are
rejected. Share-alike/non-commercial restrictions propagate to the derivative pack; they never
silently apply to TerraForge code. Creative Commons documents that `ND` prohibits distribution of
adaptations and `SA` requires compatible adaptation terms in its
[licence guide](https://creativecommons.org/share-your-work/use-remix/cc-licenses/).

## Configuration and commands

Planned `terraforge.yml` sections have no decorative settings:

| Section | Settings |
|---|---|
| `ecology` | `enabled`, `pack-path`, `game-year-days`, strict pack validation |
| `weather` | `enabled`, `mode`, update interval, bounded synoptic-cell cache |
| `flora` | `enabled`, density multiplier, seasonal overlays |
| `fauna` | `enabled`, maximum spawn attempts per tick, area capacity, cooldowns |
| `assets` | Strict asset-manifest validation and optional model integration |
| `cache` | Climate/range entries, each subject to `memory-limit-mb` |

Invalid paths, formats, IDs or manifests disable only the affected subsystem with a `[TerraForge]`
log entry. The plugin accepts no path outside the configured data root.

- `/earth ecology here` shows climate, season, community, candidates and rejection reasons.
- `/earth ecology species <id>` shows constraints, source and representation mapping.
- `/earth weather here` shows local state and synoptic-cell key.
- `/earth ecology validate` checks the selected pack and attribution before restart.

Permissions live in `plugin.yml`; admin operations default to `op`.

## Performance, safety and determinism

- Sample climate once per synoptic cell/phase, never per block.
- Add ecology to the existing bounded chunk-level sample, not a per-block cache.
- Read prepared tiles and in-memory indexes only: no SQL per column/entity/spawn attempt.
- Cap weather work and spawn attempts per tick; defer unfinished batches.
- Treat packs/configuration as untrusted: validate path, header, size and ID before use.
- Fail closed: missing range means absent species; missing climate means no ecology weather; missing
  model means no model-dependent spawn.
- Hash geographic coordinates and declared pack versions only, never wall time, iteration order or
  chunk-load counters.

## Tests and benchmarks

JUnit tests cover coordinate-to-tile lookup, antimeridian boundaries, monthly interpolation,
hemisphere seasons, restart-stable weather, range/habitat intersection, rejection reasons,
manifest/licence validation, deterministic flora, spawn capacity/cooldown and corruption/migration.
Bukkit tests mock only spawn events, global weather application and PersistentDataContainer tagging.
JMH sets ceilings for climate/ecology sampling, a flora plan and a scheduled spawn batch. A green
`./gradlew build` remains the release gate.

## 2.x release strategy

2.x should ship a dependable vertical slice first, then add fidelity without rewriting existing
worlds.

| Release | Outcome | Deliberate limit |
|---|---|---|
| 2.0 | Contracts, pack validator, climate normals, deterministic seasons/weather diagnostics and one open regional starter pack | No automatic fauna or ModelEngine dependency. |
| 2.1 | Flora plans, seasonal ground cover and signature plants with open/original assets | Structural flora only in new chunks. |
| 2.2 | Bounded fauna controller with a small curated set of faithfully represented species and provenance diagnostics | No species without verified representation. |
| 2.3 | Optional ModelEngine bridge, separately licensed resource packs and local-effect integration | No bundled proprietary assets or required client mod. |
| 2.4+ | Regions, communities, taxa, climate refinements and measured behaviour extensions | No global agent simulation or unbounded persistence. |

Each minor release ships a data-pack compatibility matrix, attribution report, benchmark results and
migration notes. 2.x also improves operations: setup previews disk/RAM before download, validation
runs in CI, `/earth ecology here` explains visible decisions, and defaults protect multiplayer TPS
over population density.

## Implementation order

1. Add records/SPIs, strict configuration and manifest validation.
2. Build CLI preparation/validation and a small attributed regional climate pack.
3. Implement tile readers, bounded caches and no-data behaviour.
4. Implement pure season/weather/community resolution and diagnostics.
5. Integrate deterministic flora plans with the generator.
6. Add the bounded Paper fauna controller and lifecycle safeguards.
7. Add optional ModelEngine and separately licensed resource packs.

Each phase ends with `./gradlew build`. Do not start a later phase until its data contract,
determinism tests and cache bounds are in place.
