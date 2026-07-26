# Configuration

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
| `chunk-cache-entries` | `4096` | resident prepared chunk samples |
| `statistics-interval-seconds` | `300` | how often cache stats are logged in debug mode |

At ~25 MB per `int16` tile, 256 tiles is ~6 GB of mapped files — address space, not heap, but keep
`memory-limit-mb` aligned with the server's actual headroom.

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

`/earth reload` (permission `terraforge.command.reload`) re-reads and re-validates the file and
clears the caches. Options that change world geometry — `scale`, `earth.origin`, `projection`,
`terrain.*` — apply to newly generated chunks only; existing chunks keep the geometry they were
generated with. Changing them on a populated world produces visible seams.
