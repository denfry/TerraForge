# Changelog

All notable changes to TerraForge are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

While the major version is `0`, a minor bump may change the world format or the prepared data
format. Both are stated explicitly per release, because either one means regenerating a world.

## [Unreleased]

**This release changes both the prepared data format and the world format.** Two migrations are
not optional:

- **Re-prepare the database.** `water_bodies` gained `surface_elevation_m` and `bed_depth_m` (the
  latter replacing `river_bed_depth_m`), so run `terraforge prepare-geo` -- or `prepare-region` --
  against every prepared `terraforge.db`. A database that predates those columns is **refused at
  startup**, and that refusal is the point: without a lake's stated altitude every lake in the world
  would be dropped without a word, so the plugin says so and falls back to the elevation-derived
  water provider for the session instead. Prepared `.tfdem` DEM tiles are **not** affected and need
  no re-preparation.
- **Regenerate the world.** Column heights change -- a block's elevation is now the mean of the DEM
  samples its footprint covers rather than the sample under its centre, and a lake now sits at its
  own altitude. Chunks generated before this release disagree with chunks generated after it, so a
  partial regeneration leaves a **permanent vertical seam** along the boundary between the two.
  Regenerate the whole world, not part of it.

### Added

- Vegetation that reads its surroundings. River and lake banks grow sugar cane, willows, tall grass,
  large ferns, orchids and firefly bushes; oases in the desert grow palms and reeds; warm beaches
  grow palms. Grassland is meadow with flower drifts of one species per patch, sunflower fields and
  bushes; temperate forest has birch and cherry groves, lilac, peony, rose bushes, leaf litter and
  mushrooms; rainforest has bamboo groves, moss carpets, dripleaf, melons and torchflowers; the
  taiga sweet berries, large ferns and fallen logs; the savanna dry grass, baobabs and shrubs; the
  steppe dead trees and cypresses; the desert flowering cacti and dry grass.
- `generation.vegetation.custom-trees` (default `true`): eight procedural trees drawn block by
  block with persistent leaves -- willow, palm, baobab, tall pine, cypress, dead tree, fallen log
  and shrub -- bounded to seven blocks' reach and twenty-four in height so they always fit the
  populator's buffer, and skipped whole rather than cut at its edge.
- `generation.vegetation.farmland` (default `true`): flat cropland becomes tilled fields, one crop
  per sixteen-block plot -- wheat, potatoes, carrots, beetroot, pumpkin patches, melons in warm
  climates -- at one ripeness, with an irrigation channel every eighth row and one plot in eight
  lying fallow. Sloping cropland stays meadow.
- Vegetation under water: lily pads and seagrass on lakes and rivers, kelp forests in temperate
  seas, coral reefs and sea pickles in warm shallows. The frozen ocean and the abyss stay bare.
- `water.min-lake-depth-blocks` (`3`), `water.min-river-depth-blocks` (`2`) and
  `water.min-ocean-depth-blocks` (`6`): depth floors, because at 20 m per block a 6 m lake and the
  30 m default ocean rounded to one block of water over a flat floor. Beds vary smoothly between
  the floor and twice it, so lakes are basins rather than slabs; real bathymetry deeper than the
  floor is untouched. **Changes column heights under water**: regenerate, do not mix.
- `water.shore-blend-blocks` (`4`): banks slope into the water at no more than one block per block
  and beds shelve out one block per block from the shore, so a lake is a basin with sloping banks
  rather than a slab of water at the bottom of a sheer-walled pit. Each chunk samples a margin that
  wide around itself, so the shaping is identical on both sides of a chunk border. `0` disables it.
  **Changes column heights near water**: regenerate, do not mix.
- The sea bed is a mix rather than gravel: sand 35 %, gravel 25 %, bone blocks 20 %, clay 15 %,
  obsidian 5 %, in five-block patches from a seedless hash so every server draws the same floor.
- The Antarctic ice sheet. South of 60° S the DEM's 2–4 km ice dome, which the vertical scale turned
  into a cliff-edged plateau of packed ice, is compressed onto a low dome (40 m at the coast, 3 % of
  the real relief inland) and every land column there is `GLACIER`: snow, with packed ice on a tenth
  of the surface. **Changes column heights south of 60° S**: regenerate, do not mix.
- `terrain.smoothing` (default `3.0`): the width, in blocks, of the ground each column's elevation
  is averaged over. At one block per kilometre and 20 m per block the world is exaggerated fifty
  to one, so every 2 % slope became a one-block step on every block and the plains read as noise.
  Widening each column's DEM footprint to a 3×3-block window is a deterministic box filter over the
  height field: seamless across chunk borders, no extra I/O, and it halves the block-to-block step.
  `1.0` restores the previous behaviour. **Changes column heights**: regenerate, do not mix.
- `generation.vegetation` (`enabled`, default `true`; `density`, default `1.0`): TerraForge's own
  vegetation pass, so biomes are no longer bare. A `BlockPopulator` places trees, grass, ferns,
  flowers, dead bushes and cacti from real land cover (how much) and climate biome (what), in this
  world's vertical frame, with none of the ore veins, lava lakes and springs that
  `vanilla-decorations` brings. Seeded from chunk coordinates, never the world seed: identical data
  and config grow identical forests on any server.
- `generation.vanilla-caves` (default `false`) hands each chunk to vanilla's cave and canyon carvers
  and its aquifer. It is off by default because those stages read vanilla's vertical frame
  (`min_y -64`, `sea_level 63`, one metre per block) from `overworld.json` and never TerraForge's:
  at `meters-per-block: 20.0` a routine 30-block vanilla cave removes 600 m of real rock, vanilla's
  `overworld_carver_replaceables` includes `minecraft:water` so its carvers breach the seabed rather
  than running under it, and the aquifer then refloods everything carved below y=63 with water (and
  below y=-54 with lava) -- 1,260 m of real elevation above that world's sea level.
- `generation.vanilla-decorations` (default `false`) hands each chunk to vanilla's whole
  `applyBiomeDecoration` pass. All of it or none of it: trees, grass and flowers arrive together with
  ore veins, `spring_water`, `spring_lava`, `lake_lava`, kelp and seagrass, at vanilla's density.
- The generator logs a WARNING when either vanilla switch is on and the world's vertical frame is
  not vanilla's, because that combination is a terrain-destroying configuration rather than a taste
  one.
- The startup banner reports which generation stages actually run (`Caves:` and `Decorations:`), and
  the jar now records the git commit it was built from plus a dirty flag
  (`terraforge-build.properties`, read by `BuildProvenance`), logged as `Build:` and `Jar sha256:`
  lines and warned about loudly when the build is dirty or unknown. A production jar was found
  containing source present in no commit, and nothing in the log could reveal it.

### Changed

- **Breaking for third-party providers** (`terraforge-core` publishes to mavenLocal):
  `WaterProvider.waterSurfaceElevation` and `riverBedDepthMeters` are gone, replaced by one
  `waterColumnAt(latitude, longitude, knownElevationMeters)` returning a
  `WaterColumn(type, surfaceElevationMeters, bedDepthMeters)` record. A column's water is one fact,
  so it is one query: one spatial-index lookup and one geometry `covers` test per column instead of
  three, and three answers that can no longer contradict each other when the bounded geometry cache
  evicts between two of them.
- `ElevationProvider` gained `averageElevationAt(lat, lon, latitudeSpan, longitudeSpan)`, defaulting
  to a point sample, and `DemTile` gained `averageWithin(...)`. Existing implementations keep
  compiling and keep point-sampling.
- `water_bodies.river_bed_depth_m` is now `bed_depth_m` and applies to lakes as well as rivers
  (HydroLAKES `Depth_avg` beside the width-derived river channel depth). The new
  `surface_elevation_m` holds the absolute water-surface elevation: HydroLAKES' `Elevation` for a
  lake, `0` for the ocean, NULL for a river, whose surface is the terrain it runs through and is
  resolved at generation time. NULL means *unknown*, and the runtime declines to place that water
  body at all rather than read it as sea level.

### Removed

- The `vegetation` config section (`vegetation.enabled`, `vegetation.density`). `density` was never
  read by any code path, and `enabled` was never TerraForge's own vegetation -- it was the switch for
  vanilla's entire decoration pass, which is now named `generation.vanilla-decorations` and is off by
  default. TerraForge's own vegetation now lives under `generation.vegetation` (see Added). An
  existing `terraforge.yml` keeps loading; the old top-level keys are ignored.

### Fixed

- `generation.caves` did not generate TerraForge's karst caves. It was returned from
  `ChunkGenerator.shouldGenerateCaves()`, which is Paper's switch for *vanilla's* carvers and
  aquifer, while `KarstCaveCarver` ran unconditionally regardless of the setting. In a scaled world
  the flag documented as "TerraForge's karst caves" was therefore quietly enabling the vanilla
  worldgen that a scaled vertical frame cannot survive: the audited world held 249,150 fluid blocks
  inside dry Tibetan rock per region file, and 26.20 % of Gulf-of-Guinea ocean columns contained air.
  `generation.caves` now drives the karst carver and nothing else; vanilla's stages have their own
  two switches, both off.
- Every mountain lake generated as a dry lake bed painted with `Biome.RIVER`. The runtime answered
  `0.0` -- sea level -- for every lake's surface elevation, because the prepared schema had nowhere
  to store the real one. Combined with an uncommitted clamp in the deployed jar, that deleted up to
  5,540 m of Tibetan plateau. A lake now generates at its own altitude with its bed carved below its
  own surface, and a lake whose altitude the source does not state is not placed at all.
- Terrain was rougher than the Earth. One block's elevation came from a point sample at the block
  centre, which at coarse `blocks-per-km` reads one of the many DEM samples the block covers and
  turns the difference between two adjacent samples into a block-to-block step. Averaging over the
  block's geographic footprint cut mean absolute block-to-block height difference from 12.75 to 10.71
  in the Himalaya and from 10.66 to 8.83 in the Alps -- about a fifth of the roughness was the
  sampler's own. The average is deterministic, bounded at 16 samples per axis, reads neighbouring
  tiles, counts a shared tile edge once, and falls back to interpolating the centre when the
  footprint is finer than the DEM grid. `/earth whereami` and the CLI still point-sample: they ask
  about a place, not about a block.
- A point sitting almost entirely over a void was given an elevation anyway. Bilinear interpolation
  renormalised over whichever corners happened to hold data, so one distant sample could speak for a
  point that had none, and the result was indistinguishable from measured ground. A height is now
  reported only when at least half the bilinear weight came from prepared samples. Coastal behaviour
  is unchanged -- a point mostly over real data still keeps its own height.
- The shipped `terraforge.yml` disagreed with the documented defaults and with
  `TerraForgeConfig.defaults()`: `blocks-per-km` was `2.0` and `min-visible-river-discharge-cms` was
  `40.0`, against `1.0` for both. A fresh install generated a different world from the one the docs
  describe.
- Glacier terrain below 2000 m no longer maps to vanilla's `ICE_SPIKES` biome. That biome carries its
  own noise-driven "ice_spike" decorator feature with no relationship to real glacier surfaces --
  visible as an erratic forest of packed-ice columns wherever a coastal snowfield was mapped. It now
  uses `SNOWY_SLOPES`, matching the biome already used above the treeline.
- A landcover pixel alone (`PERMANENT_WATER`, e.g. ESA WorldCover) no longer assigns a lake biome to a
  column the vetted vector water data disagrees with. ESA WorldCover is known to misclassify bright
  arid ground -- salt pans, playas -- as permanent water, which previously painted dry land in arid
  regions with a lake/river biome and surface palette even though no water block was ever placed
  there.
- Added `water.min-visible-river-discharge-cms` (default `1.0`) so a prepared world does not have to
  render every HydroRIVERS headwater trickle as a full carved channel. HydroRIVERS' minimum-width
  floor at coarse `blocks-per-km` settings turned the whole connected network, including the smallest
  mapped streams, into a dense, hairy web rather than distinct rivers. The setting only changes what a
  running world renders; `prepare-region`/`prepare-geo` still keep every prepared river (with its
  discharge now stored in `water_bodies.discharge_cms`), so raising the threshold later needs no
  re-preparation. `0.0` restores the previous behaviour.

## [0.1.2] - 2026-08-05

### Added

- `bluemap.max-city-markers` and `bluemap.min-city-population` in `terraforge.yml` cap how many city
  markers get published to the web map. A dense gazetteer can hold tens of thousands of places;
  publishing all of them flooded the map with marker pins and made the browser lag. Defaults dropped
  from an effective 2000/0 to 300/5000.

- Managed Earth world lifecycle: `/earth world plan|create|status|verify|abort` stages the primary
  `earth` world (server.properties, bukkit.yml, paper-world.yml and the height datapack) behind a
  recoverable transaction, verifies it against the live server after restart, and refuses to overwrite
  or delete an existing `earth` world directory. Replaces the previous Multiverse / manual
  datapack-copy workflow, which cannot work on Paper 1.21.8+: only the primary world may carry a
  non-vanilla dimension type. See [installation.md](docs/installation.md).
- Bounded pregeneration lifecycle: `/earth pregenerate start|full|pause|resume|status|cancel`, with
  checkpointing to `plugins/TerraForge/pregeneration.json`, config/data fingerprint checks on resume,
  and automatic pause/resume driven by live TPS, MSPT, disk space and online-player policy
  (`pregeneration.*` in `terraforge.yml`). A restart always leaves a job paused for manual resume.
  Cancelling a job never deletes chunks already generated.
- `/earth data status`, `/earth doctor` and `/earth performance` — prepared-data inventory, independent
  health diagnostics and live server/pregeneration health, respectively.
- `terraforge setup`: one command that creates the plugin data directory, downloads every source
  dataset a bounding box needs, prepares it and validates the result. Preparing a region no longer
  starts with five browser tabs and a manual directory layout.
- `terraforge fetch`: downloads Copernicus DEM (GLO-30 or GLO-90), ESA WorldCover, Natural Earth
  boundaries and lakes, and the GeoNames gazetteer for a bounding box, from mirrors that need no
  account. `--dry-run` reports the download size first; a re-run fetches only what is missing.
- `terraforge init`: creates the plugin directory tree and a `terraforge.yml` whose origin and
  test region match the box the data was prepared for. An existing config is never rewritten
  without `--replace-config`.
- Land-cover preparation now reads GeoTIFF as well as Arc/Info ASCII, subsampling each source into
  one prepared grid per degree cell (`--samples-per-degree`, default 600).
- Boundary and water imports now read Natural Earth's published attributes as well as TerraForge's
  own schema. Territories with no ISO 3166-1 code are reported and skipped instead of failing the
  import; reservoirs are skipped as man-made water.
- `fetch` downloads the WHYMAP WOKAM karst aquifer map from BGR as the `karst` dataset and unpacks
  every shapefile in it, so generated caves can be constrained to rock caves actually form in.
  Preparation reads WOKAM's polygon layers directly and ignores the archive's other layers.
- `prepare-region --skip` runs a subset of the stages (`dem`, `bathymetry`, `landcover`,
  `database`), and `--replace-database` rebuilds the vector tables without rewriting prepared
  tiles. Adding a dataset to a prepared region no longer means re-transcoding every DEM tile.
- Natural water geometry now decodes lazily from the prepared database instead of loading every
  feature at startup; a whole-Earth import no longer stalls the server thread while water bodies
  are parsed. Resident decoded features are bounded by the new `cache.water-feature-cache-entries`
  setting (default 4096).

### Fixed

- Ocean, lake and river columns with no DEM coverage were reported as plain land (`water NONE`,
  falling back to elevation 0 m / beach) instead of consulting the prepared vector water data, which
  does not need a DEM to know a point is ocean. A point far outside the prepared elevation coverage
  now still gets classified from real coastline/lake/river geometry when it exists.
- Karst-constrained caves carved without checking whether the column, or any of the neighbouring
  columns two blocks out, was water. Karst polygons that graze a coastline could open a cave mouth
  straight into the seabed. Each carved column is now capped by its own surface height and skipped
  entirely when it is water.
- The plugin now enables at `STARTUP` load order, so Bukkit asks TerraForge for the `earth` world's
  generator instead of silently falling back to vanilla terrain.
- The managed-world check compares absolute, normalized paths, so a `world-container` relative to
  the server root is resolved consistently with the live world directory.

- The bathymetry stage counted progress in source files rather than degree cells. Eight GEBCO
  rasters cover the planet, so the progress line sat at `0/8` for the first hour and reported no
  usable rate or eta — indistinguishable from a hang. Every stage now announces itself before it
  works rather than after, and the vector import names each file as it opens it.
- HydroRIVERS attribution was missing from the licence summary `fetch` prints, although the dataset
  is CC BY 4.0 and requires it.

- Preparing a DEM from a tiled COG read one row at a time, decoding every tile it crossed hundreds
  of times over: a single Copernicus GLO-30 tile took over ten minutes. Rows are now read in strips,
  which brings the same tile down to about five seconds.

## [0.1.1] - 2026-07-29

### Changed

- Reworked the public README with a project banner, compatibility matrix, quick start, command
  reference and clearer preview-status messaging.
- Added the MIT licence, contribution guide, support policy, code of conduct, security policy,
  issue forms, pull request template and release runbook.
- Hardened GitHub Actions with immutable action SHAs, least-privilege permissions, wrapper
  validation, concurrency limits, exact artifact selection and SHA-256 release checksums.
- Made every Gradle archive reproducible, added implementation metadata to jar manifests and
  embedded the TerraForge licence in distributable jars.
- Added tag-driven Modrinth publishing for the Paper plugin.

### Fixed

- Fail closed when the prepared DEM directory cannot be opened instead of continuing with a
  partially initialized plugin.
- Reject configured data paths that escape the TerraForge plugin directory.

## [0.1.0] - 2026-07-27

First tagged release. A world generated with this version has the real relief, coastlines, water
and land cover of the Earth, and nothing man-made.

### World generation

- Earth coordinate system: `GeoPoint`, `EarthLocation`, `CoordinateTransformer`, geodesic distances
  (Vincenty) and bearings.
- Projections: Web Mercator, equirectangular and plate carrée, selected in `terraforge.yml`.
- DEM pipeline: `.tfdem` tiles written offline, memory-mapped and sampled at runtime, with voids
  carried through preparation rather than filled.
- Terrain generation: surface palette, vertical scaling, bedrock, sea level and ocean floor.
- Water from prepared natural-water polygons; land cover from prepared `.tflc` grids, both falling
  back conservatively when coverage is missing.
- Biomes derived from land cover, refined by elevation, latitude and water state.
- Natural features only. Roads, buildings, railways, bridges, airports and power lines have no code
  path, and the plugin refuses to start if any of them is switched on.

### Server

- `/earth` — `info`, `whereami` (country, region, nearest place, real elevation), `coords`,
  `distance`, `country`, `city`, `teleport city|country`, `cache`, `pregenerate`, `towny`, `debug`
  (one-off report and a live per-player overlay) and `reload`, with tab completion for arguments.
- `/earth reload` re-validates the config, clears caches and reloads the prepared geography without
  a restart. The generator is deliberately **not** hot-swapped: that would seam the world.
- Bounded pregeneration driven from in-game, one chunk per tick.
- Bounded, instrumented caches for DEM tiles, chunk samples and geography.

### Integrations

- **Towny/NewTowny**: towns annotated with real coordinates, elevation, country and region on
  creation, spawn move and rename, and forgotten on deletion. `/earth towny refresh` backfills towns
  that predate the plugin. SQLite writes run on their own thread; Towny and Bukkit are read only on
  the server thread.
- **BlueMap**: cities, capitals, countries and regions published as TerraForge-owned marker sets,
  re-published after every BlueMap reload. Only sets prefixed `terraforge-` are ever touched, so
  Towny's own markers are left alone. `GeoMarkerService` lets other plugins register markers with or
  without BlueMap installed.

### Offline CLI

- `prepare-dem` — SRTM `.hgt` and north-up WGS84 GeoTIFF into `.tfdem` tiles.
- `prepare-boundaries`, `prepare-cities`, `prepare-landcover`, `prepare-geo` — administrative
  boundaries, the GeoNames gazetteer with alternate names, land cover and natural water.
- `prepare-region` — every dataset for one bounding box in a single pass and one transaction.
- `validate` — overlapping boundaries, duplicate ISO codes and names, misplaced regions and cities,
  missing or duplicated capitals, plus a coverage report. `--strict` makes it a CI gate.
- `pregenerate` — plans a chunk set and checks DEM coverage without writing anything.
- `info` — what the configured world will look like.

[Unreleased]: https://github.com/denfry/TerraForge/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/denfry/TerraForge/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/denfry/TerraForge/releases/tag/v0.1.0
