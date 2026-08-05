# Configuration

## Caves and structures

`generation.caves` defaults to `false`. When enabled, TerraForge generates deterministic cave
geometry only inside prepared WOKAM karst polygons; nearby OSM `natural=cave_entrance` points can
anchor passages. Neither dataset provides global 3D cave surveys.

`generation.man-made-structures` defaults to `false`; keep it disabled to prevent villages,
temples, mineshafts, strongholds, portals, shipwrecks and other human-made vanilla structures.

The file lives at `plugins/TerraForge/terraforge.yml` and is created from the bundled default on
first start. Every option is honoured by the runtime; validation is strict and the plugin refuses to
enable on an invalid configuration rather than generating a broken world.

## `world`

| Key | Default | Meaning |
|---|---|---|
| `name` | `earth` | name of the world TerraForge generates |

## `scale`

| Key | Default | Meaning |
|---|---|---|
| `blocks-per-km` | `1.0` | blocks per kilometre of ground. Supported: 0.5, 1.0, 2.0, 5.0 |

See [projection.md](projection.md) for the resulting world sizes.

## `earth`

| Key | Default | Meaning |
|---|---|---|
| `origin.latitude` | `51.0` | geographic point mapped to Minecraft `0, 0` |
| `origin.longitude` | `10.0` | |
| `projection` | `equirectangular` | `equirectangular`, `web_mercator` or `plate_carree` |

## `terrain`

| Key | Default | Meaning |
|---|---|---|
| `sea-level` | `63` | Y of sea level |
| `min-y` | `-64` | world bottom |
| `max-y` | `320` | world top |
| `vertical-exaggeration` | `1.0` | multiplier on real elevation (0.5–3.0) |
| `meters-per-block` | `1.0` | vertical metres per block |
| `fallback-elevation` | `0.0` | elevation used where no DEM tile exists |
| `bedrock-thickness` | `8` | bedrock layers at the world bottom |

Out-of-range heights are soft-clamped, never flattened.

## `water`

| Key | Default | Meaning |
|---|---|---|
| `oceans` | `true` | generate oceans and seas |
| `lakes` | `true` | generate inland standing water |
| `rivers` | `true` | generate natural watercourses |
| `default-ocean-depth` | `30.0` | depth in metres where the DEM has no bathymetry |
| `min-visible-river-discharge-cms` | `1.0` | HydroRIVERS discharge (m³/s) below which a prepared river is not carved |

When `data.database-file` exists and contains prepared entries in `water_bodies`, TerraForge loads
those natural WKB geometries into memory at startup. A missing, empty or unreadable database is
logged and safely falls back to elevation-derived oceans; it never floods unknown data as water.

`min-visible-river-discharge-cms` filters what the running world renders, not what preparation keeps.
HydroRIVERS maps the entire connected network down to the smallest headwaters, and every prepared
line stays in `water_bodies` regardless of this setting -- raising or lowering it later is a config
reload, never a re-`prepare-region`. At coarse `blocks-per-km` the effect matters most: a real 5 m
trickle still floors to a one-block-wide channel (see [data-sources.md](data-sources.md#rivers)), so
rendering every mapped headwater turns into a dense, hairy web rather than distinct rivers. `0.0`
restores the old behaviour of carving every prepared river.

Prepare source water data offline with `terraforge prepare-geo --input <directory> --database
<file>`. Lakes and oceans use GeoJSON `Polygon` or `MultiPolygon` features. River GeoJSON may use
`LineString` or `MultiLineString` with `DIS_AV_CMS`; `prepare-region` also accepts the official
HydroRIVERS `.shp` + `.dbf` pair directly. River lines are widened and their beds carved during
offline preparation. Existing water data is protected; pass `--replace-water` only when deliberately
rebuilding it.

## `biomes`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | map real land cover to Minecraft biomes; `false` uses plains everywhere |
| `edge-noise` | `0.35` | dithering at biome borders so class edges are not pixelated; `0` disables |

## `vegetation`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | natural trees, grass, flowers, cacti, snow, ice |
| `density` | `1.0` | multiplier on vegetation density |

Vegetation is natural only. No villages, huts or man-made objects exist in any code path.

## `generation`

| Key | Default | Meaning |
|---|---|---|
| `natural-only` | `true` | no structures of any kind. Recommended, and the default |
| `worker-threads` | `4` | threads for asynchronous chunk data preparation |

## `pregeneration`

| Key | Default | Meaning |
|---|---|---|
| `max-in-flight` | `1` | maximum chunks requested concurrently. Never unbounded by design |
| `pause-when-players-online` | `true` | auto-pause the job while any player is online |
| `minimum-tps` | `18.0` | auto-pause below this server TPS |
| `maximum-mspt` | `40.0` | auto-pause above this average tick time (ms) |
| `stable-resume-seconds` | `15` | how long health must stay good before an auto-paused job resumes on its own |
| `minimum-free-disk-gb` | `10` | auto-pause (and refuse `/earth pregenerate resume`) below this usable disk space; also the minimum required for `/earth world plan`/`create` |
| `checkpoint-every-chunks` | `128` | how often progress is written to `plugins/TerraForge/pregeneration.json` |

Auto-pause and auto-resume only apply to a job already `RUNNING`; a restart never resumes a job by
itself — see [pregeneration.md](pregeneration.md) and [installation.md](installation.md).

## `infrastructure`

All six keys (`roads`, `buildings`, `railways`, `bridges`, `airports`, `power-lines`) default to
`false` and **must stay false** — setting any to `true` aborts startup with a clear message.
TerraForge has no code that builds man-made features; the section exists so the guarantee is
explicit and auditable.

## `data`

| Key | Default | Meaning |
|---|---|---|
| `data-directory` | `data` | prepared tiles, relative to `plugins/TerraForge/` |
| `cache-directory` | `cache` | on-disk caches |
| `database-file` | `terraforge.db` | SQLite geographic database |

## `cache`

| Key | Default | Meaning |
|---|---|---|
| `memory-limit-mb` | `1024` | soft ceiling across all in-memory caches |
| `dem-tile-cache-entries` | `256` | resident DEM tiles |
| `landcover-grid-cache-entries` | `256` | resident prepared land-cover grids |
| `chunk-cache-entries` | `4096` | resident prepared chunk samples |
| `statistics-interval-seconds` | `300` | how often cache stats are logged in debug mode |

At ~25 MB per `int16` tile, 256 tiles is ~6 GB of mapped files — address space, not heap, but keep
`memory-limit-mb` aligned with the server's actual headroom.

Land-cover grids are heap, not mapped: at the default 600 samples per degree a grid is ~360 KB, so
256 of them is ~92 MB. Both caches are bounded by count *and* by `memory-limit-mb`, whichever binds
first, so neither can be made to exceed the budget by raising the other. This is what makes the size
of a prepared world a disk question rather than a memory one — a whole-Earth import is tens of
thousands of grids on disk and the same 92 MB resident as a single test region.

## `towny` / `bluemap`

| Key | Default | Meaning |
|---|---|---|
| `towny.enabled` | `true` | enable the Towny bridge when Towny is installed |
| `bluemap.enabled` | `true` | enable the BlueMap bridge when BlueMap is installed |
| `bluemap.city-markers` | `true` | show real-world city labels |
| `bluemap.country-labels` | `true` | show country labels |

Both are soft dependencies: a missing plugin is logged and ignored, never fatal.

## `debug`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` | verbose generator logging |
| `per-player` | `false` | let players with `terraforge.command.debug` toggle their own overlay |

## `test-region`

The bounded area used for development and validation. Defaults to central Europe
(47–55.5°N, 5–15.5°E), covering Germany and its neighbours.

```yaml
test-region:
  name: central-europe
  latitude-min: 47.0
  latitude-max: 55.5
  longitude-min: 5.0
  longitude-max: 15.5
```

## Reloading

`/earth reload` (permission `terraforge.command.reload`) is a **data** reload, not a generator
swap. It:

1. re-reads and re-validates `terraforge.yml` — an invalid file is reported and nothing else runs;
2. clears the caches;
3. reloads the prepared database (countries, regions, cities) and republishes the BlueMap markers.

Step 3 is safe while players are online because none of it is geometry: it is lookup data that
`/earth whereami`, `/earth city`, `/earth teleport` and the web map read.

The generator itself is **deliberately not** hot-swapped. `scale`, `earth.origin`, `projection` and
`terrain.*` keep running with the values the server started with; changing them takes a restart.
This is not an oversight — a live swap would generate new chunks against a different projection
than their neighbours, leaving a permanent seam through the world with no way back.

After re-running `prepare-region`, `/earth reload` is enough to pick up new boundaries and cities.
Towns already annotated keep the country they were resolved against; run `/earth towny refresh` to
re-resolve them.
