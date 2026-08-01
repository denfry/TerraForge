# Changelog

All notable changes to TerraForge are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

While the major version is `0`, a minor bump may change the world format or the prepared data
format. Both are stated explicitly per release, because either one means regenerating a world.

## [Unreleased]

### Added

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

### Fixed

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
