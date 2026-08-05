# Changelog

All notable changes to TerraForge are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

While the major version is `0`, a minor bump may change the world format or the prepared data
format. Both are stated explicitly per release, because either one means regenerating a world.

## [Unreleased]

### Fixed

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
