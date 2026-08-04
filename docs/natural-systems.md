# Natural systems roadmap (planned, 2.x+)

Status: design roadmap, not implemented. This document specifies the physical natural systems that
complete TerraForge after terrain generation: hydrology, geology, atmosphere, oceans, cryosphere,
geomorphology, soundscape and an in-world field atlas. It complements [ecology.md](ecology.md):
ecology decides what can live at a place; these systems define the physical environment.

## Product rule

TerraForge prepares real-world evidence offline and renders a stable approximation at the configured
world scale. It never creates roads, buildings, dams, farms, ports or other human infrastructure.
It does not continuously simulate the planet. Each subsystem reads prepared data, returns an
immutable sample, and acts through bounded chunk generation or scheduled Paper adapters.

~~~text
prepared geodata
  -> core provider contracts
  -> geo tile/index readers and bounded caches
  -> pure natural-system resolvers
  -> terrain, ecology and Paper adapters
  -> generated chunks / local effects / diagnostics
~~~

The same geographic input, configuration, pack version and game day must produce the same output
after restart. No subsystem may do HTTP, raw raster/vector parsing, SQL per block, unbounded caching
or heavy GIS work on the Paper server thread.

## Existing baseline

| System | Current behaviour | Gap |
|---|---|---|
| Terrain | DEM-driven heights with vertical scaling | No geology-aware materials or slope processes |
| Oceans | Real bathymetry when prepared; otherwise configurable default depth | No currents, tides, reef logic or coastal processes |
| Rivers | HydroRIVERS lines are buffered offline, then a shallow bed is carved | No flow direction, rapids, waterfalls, springs or deltas |
| Lakes | Prepared natural lake polygons classify water | Current runtime water surface defaults to sea level, so high-altitude lakes need a per-lake elevation fix |
| Caves | Optional deterministic passages inside prepared karst polygons | No surveyed cave geometry, underground water or geology layers |
| Land cover | Surface/biome signal for forest, wetland, bare ground, snow and mangrove | No soil, substrate or seasonal surface state |
| Climate biomes | Latitude, elevation, water and land cover resolve a biome | No monthly climate, local weather, true seasons or astronomy |
| Ecology | Planned in [ecology.md](ecology.md) | Depends on the physical layers in this roadmap |

The first hydrology task is correctness: preserve each lake's measured surface elevation in prepared
water data. A lake polygon says where water is, but it does not say its level.

## Shared contracts

terraforge-core receives value records and SPIs only. They contain no JTS, SQL, Paper or model
types.

| SPI / record | Purpose |
|---|---|
| HydrologyProvider, HydrologySample | Waterbody, measured surface, flow direction, discharge band, channel class and catchment state |
| GeologyProvider, GeologySample | Lithology, soil class/depth, substrate, permeability, fault/volcanic state and slope material |
| AtmosphereProvider, AtmosphereSample | Solar position, day length, cloud, visibility, wind, temperature and precipitation normals |
| OceanProvider, OceanSample | Bathymetry, coast class, tide phase, current class, sea ice and reef suitability |
| CryosphereProvider, CryosphereSample | Permanent ice, seasonal snow, glacier state, freeze/thaw and snowmelt signal |
| NaturalFeatureProvider, NaturalFeatureSample | Prepared waterfall, rapid, spring, delta, dune, moraine, lava field and geothermal masks |
| NaturalSystemDiagnostics | Stable operator-visible explanation of every value and source pack |

terraforge-geo owns prepared-tile readers, spatial indexes and every cache. Pure resolvers may
combine samples but cannot load files. terraforge-generator converts deterministic plans to blocks.
terraforge-plugin owns bounded live effects, commands and Paper events.

## Hydrology

### Goal

Rivers follow a prepared network, lakes retain their own elevation, and visible river behaviour
matches a local terrain break. Hydrology becomes shared input for ecology, surface material,
wetlands, snowmelt and spawning.

### Prepared data

A hydrology pack contains natural water polygons and river lines with provenance, version and
licence. Preparation clips source geometry, rejects artificial canals/reservoirs, resolves line
direction, samples the DEM and stores:

- water type: ocean, lake, river, wetland or no data;
- surface elevation in metres;
- one of eight flow directions plus slope magnitude;
- discharge band: absent, intermittent, small, medium, large or major;
- channel class: calm, rapid, waterfall, delta distributary, estuary or dry;
- catchment class: glacier-fed, rainfall-fed, seasonal, arid/intermittent or unknown;
- natural feature ID where specialised geometry is required.

The CLI validates downstream continuity. A river segment cannot increase altitude without an
explicit exception. Incomplete data produces no data, never an invented river.

### Waterfalls, rapids and springs

A waterfall is not a random vertical strip of water. prepare-hydrology marks one only where a
prepared river crosses a persistent local elevation drop above the configured visible threshold. It
stores crest direction, drop height, channel width, plunge-pool extent and downstream anchor.

The generator writes a stable rock face, crest water, falling-water plane, carved plunge pool and
downstream channel only inside that prepared mask. It checks chunk boundaries so adjacent chunks
meet without duplicate waterfalls. At coarse scale, nearby falls merge into one representative
cascade with a diagnostic note.

Rapids use a lower threshold, a stepped bed and geology-compatible boulders. Springs require source
evidence or a prepared karst/groundwater outlet. Neither feature appears from noise.

### Lakes, wetlands and deltas

Lake preparation stores observed surface elevation per polygon and, for large lakes, per raster
cell. Runtime fills to that level. Wetlands use water proximity, flatness, soil permeability and
seasonal saturation rather than a biome label.

Deltas require a natural distributary network and low-gradient coast. They may place mudflats,
reeds and shallow channels. They cannot build levees, canals or ports.

### Runtime limits

Hydrology is generation-time data. Only safe snow/ice cover and ecology conditions update later.
No fluid simulation, erosion loop or world-wide river tick runs on the server.

## Geology and soils

### Goal

The terrain exposes plausible substrate. Mountains need more than generic stone, deserts need more
than sand, and plants respond to soil depth, drainage and rock rather than a biome name.

### Model

GeologySample combines:

- bedrock family: igneous, sedimentary, metamorphic, carbonate, volcanic, unconsolidated or unknown;
- surface material: gravel, sand, clay, peat, loam, scree, till, ash or exposed bedrock;
- soil depth and drainage band;
- permeability plus carbonate/organic signal;
- slope class: flat, gentle, steep or cliff;
- optional fault, volcanic and geothermal masks.

The CLI rasterises source maps to blocks-per-km with documented majority/priority rules. It omits
features the selected scale cannot show instead of fabricating decoration.

### Generation rules

| Environment | Material plan |
|---|---|
| Limestone/karst | Limestone-like stone, sinkhole candidates, karst cave eligibility and spring suitability |
| Granite/metamorphic mountains | Exposed stone, boulder fields, thin soils and scree on steep slopes |
| Volcanic ground | Basalt/blackstone-like palette, ash/tephra surface and lava-field masks where evidence exists |
| Alluvial valley | Deep soil, gravel/sand bars, broad riverbank vegetation and wetland suitability |
| Glacial till/moraine | Mixed gravel, boulders, shallow lakes and alpine/meadow ecology |
| Peatland | Mud/peat-like surface, saturation and water-tolerant flora |
| Dunes | Sand substrate and prepared dune ridges, not moving-sand physics |

Geology affects new chunks only. It can refine the palette of optional karst caves but never claim
a real surveyed cave or collapse player terrain.

## Atmosphere, seasons and astronomy

### Goal

Day length, sun angle, weather probability, snow and ecology agree at the same coordinate and
calendar date.

A world has a configurable tropical-year length. AtmosphereProvider turns game day and latitude into
solar declination, day length, solar altitude and season. It supports polar day/night, equinox and
solstice transitions without using real clock time.

Climate normals from ecology supply expected temperature, humidity, wind, cloud, visibility and
precipitation. A deterministic weather front drives temporary local state.

Paper has one time and weather state per world, so it cannot draw different local suns or rain for
distant players without a client capability. 2.x therefore offers:

- global world time/weather from the dominant online region, with visible diagnostics;
- local solar and climate values for gameplay, ecology and resource-pack-capable effects;
- optional client integrations for sky tint, cloud density, aurora, fog and precipitation.

Natural seasonal effects require data support: snow accumulation, lake ice, leaf/ground variants,
migration and dormancy. They use safe overlays or new chunks, not uncontrolled block replacement.

## Oceans and coasts

### Goal

Coasts reflect bathymetry, exposure and biological suitability. OceanProvider supplies depth, shore
class, sea ice, reef/kelp potential and tide phase without simulating the ocean globally.

OceanSample stores bathymetry, coast type, shelf/deep-ocean band, offshore exposure, current class,
sea-ice probability and optional tidal range. Prepared bathymetry has priority. When absent, the
existing default ocean depth remains an explicit diagnostic fallback.

Tides are initially a visual/ecological signal. They choose intertidal material, mangrove/salt-marsh
suitability and beach state. Dynamic water-level changes require a separate opt-in mode because they
can alter player builds.

| Coast | Generation effect |
|---|---|
| Sandy beach | Sand/gravel bands, dune eligibility and beach flora |
| Rocky shore | Exposed geology, prepared tide pools and sparse flora |
| Estuary | Brackish mud, low-gradient channels and wetland ecology |
| Coral coast | Reef suitability only with warm shallow clear-water evidence and available assets |
| Kelp coast | Cold/temperate shallow-water vegetation suitability |
| Ice coast | Sea ice, packed ice shore and cold-adapted ecology |
| Cliff coast | Geology-led rock face and prepared waterfall eligibility |

## Cryosphere

CryosphereSample records permanent ice, snow probability/depth, freeze-thaw, glacier mask,
glacier-fed runoff and sea-ice probability. It uses prepared glacier/ice masks and climate normals,
then feeds ecology, hydrology, surface selection and atmosphere.

Generation can produce ice/snow surfaces, crevasse masks, moraines, proglacial lakes and
glacier-fed river eligibility only from prepared features. It does not move glaciers, erase player
terrain or simulate avalanche damage.

## Geomorphology and natural features

DEM shape misses many natural forms at coarse scale. A feature layer adds only mapped or defensibly
derived forms that players can recognise.

| Feature | Evidence | Generation contract |
|---|---|---|
| Waterfall / rapid / spring | Hydrology network plus DEM and outlet data | Deterministic prepared feature mask |
| Dune field | Land cover, sand substrate and prepared ridge orientation | Static ridge/bowl plan; no moving sand |
| Moraine / glacial valley | Glacier/terrain feature mask | Till, boulders, slope profile and proglacial water |
| Lava field / volcanic cone | Volcanic/geologic evidence | Static terrain/material forms; no eruption |
| Geothermal spring | Geothermal source/mask | Water/steam only where source permits |
| Sinkhole | Karst plus terrain evidence | New-chunk depression; no player collapse |
| Talus/scree | Steep slope plus compatible geology | Boulders/gravel and thin soil |
| Floodplain | River, low gradient and alluvial substrate | Natural banks, wet ground and ecology input |

Every feature has a prepared footprint, minimum visible size and deterministic merge rule.

## Soundscape

SoundscapeResolver chooses a small ambient profile from ecology, hydrology, atmosphere and hour:
forest birds, insects, frogs, wind, river roar, surf, ice crack or desert silence.

The server schedules only bounded, player-proximate sounds. It never creates invisible entities,
plays a sound per block or transmits data for unloaded regions. Profiles reference optional
resource-pack IDs; absent assets produce silence, not an unrelated substitute.

## Field atlas and diagnostics

The field atlas is a research/moderation layer, not a gameplay advantage.

- /earth identify reports natural feature, material, species/community and source confidence.
- /earth hydrology here reports water type, surface elevation, discharge/channel class, flow
  direction and waterfall/rapid decision.
- /earth geology here reports substrate, soil, slope and material plan.
- /earth atmosphere here reports solar state, season, climate and weather decision.
- /earth ocean here reports bathymetry, coast, tide and ice state.
- /earth natural validate checks loaded packs, notices, checksums and coverage.

Commands expose source identity and fallback status. They do not expose sensitive raw occurrence
coordinates, writable paths or internal exception traces. Admin permissions default to op.

## Pack integrity and licences

Natural-system packs use versioned manifest, source registry, attribution notices, SHA-256
checksums, declared coverage, bounded tile dimensions and strict path containment.

~~~text
natural-pack/
  manifest.json
  hydrology/<tile>.tfhydro
  geology/<tile>.tfgeo
  ocean/<tile>.tfocean
  cryosphere/<tile>.tfice
  features/<tile>.tfnatural
  attribution/NOTICE.md
  attribution/sources.json
~~~

The runtime rejects missing attribution, invalid geometry, path traversal, unsupported formats,
duplicate IDs, oversized tiles and checksum mismatches. It disables only the affected subsystem and
retains safe terrain fallbacks. Creative assets remain separate resource packs under
[ecology.md](ecology.md).

## Performance and tests

- Add each provider result to bounded chunk/synoptic-cell samples, never a per-block cache.
- Load tiles and build spatial indexes at startup/reload only.
- Cap live weather, sound and fauna work per tick; defer unfinished work.
- Register every cache through ManagedCache and keep it under memory-limit-mb.
- Run no database query in terrain sampling, feature placement or a spawn attempt.
- Treat source/configuration data as untrusted and fail closed.

JUnit covers tile lookup, no-data boundaries, antimeridian seams, deterministic hashes, source
validation, hydrology continuity, lake elevation, feature merging, seasonal transitions and fallback.
Bukkit boundary tests cover global weather, sound scheduling and command permissions. JMH covers a
provider sample, complete chunk plan and bounded scheduled batch. The full Gradle build remains the
release gate.

## Release sequence

| Release | Focus | Exit criterion |
|---|---|---|
| 2.0 | Ecology foundations, climate normals and weather diagnostics | Attributed starter pack and deterministic samples |
| 2.1 | Hydrology correctness and flora | Lake-level fix, river classes, first waterfalls/rapids and seasonal surfaces |
| 2.2 | Geology, soils and bounded fauna | Substrate-aware materials plus a small faithful species set |
| 2.3 | Atmosphere, astronomy and cryosphere | Calendar/solar model, safe snow/ice and diagnostics |
| 2.4 | Oceans, coasts, soundscape and ModelEngine bridge | Separate asset packs; no proprietary assets bundled |
| 2.5+ | Deltas, geothermal areas, dunes, moraines and regional packs | Every feature has data, benchmarks and attribution |

Each release documents coverage, input data, fallback, disk/RAM cost, benchmark result and migration.

## Implementation order

1. Fix lake surface elevation and extend WaterProvider into HydrologyProvider.
2. Define common pack manifest/attribution validation shared with ecology.
3. Add bounded hydro/geology/ocean/cryosphere readers in terraforge-geo.
4. Build pure resolvers and diagnostics before any Paper adapter.
5. Add deterministic generation plans for waterfalls, features, geology and flora.
6. Add bounded live adapters for weather, sound and fauna.
7. Add optional resource/model integrations as separately licensed distributions.

Every phase ends with a green build and determinism tests.
