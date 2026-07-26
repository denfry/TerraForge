# Troubleshooting

## The plugin refuses to enable

```
[TerraForge] Invalid configuration: infrastructure.* must all be false ...
[TerraForge] Fix plugins/TerraForge/terraforge.yml and restart.
```

TerraForge fails fast rather than generating a world that is wrong in a way you cannot undo. The
message names the exact key. Common causes:

| Message contains | Fix |
|---|---|
| `infrastructure.* must all be false` | set every `infrastructure` key to `false` |
| `sea-level must lie between` | `min-y < sea-level < max-y` |
| `blocks-per-km must be greater than 0` | use 0.5, 1.0, 2.0 or 5.0 |
| `Unknown projection` | use `equirectangular`, `web_mercator` or `plate_carree` |

## The world is flat / all ocean

The DEM is not being found. Check:

1. `plugins/TerraForge/data/dem/` contains `.tfdem` files (not `.tif`).
2. The tiles cover your play area — file names encode their south-west corner, e.g. `N50E008`.
3. The log for `Missing DEM tile`.

`.tif` files are not read at runtime; run `terraforge prepare-dem` first (see [dem.md](dem.md)).

## Missing DEM tile warnings

```
[TerraForge-Geo] Missing DEM tile: N52E013 (no prepared file); falling back to
terrain.fallback-elevation (0.0 m)
```

Not fatal — that area becomes flat land at the fallback elevation. Prepare the missing tiles and
regenerate the affected chunks (already-generated chunks are not rewritten). The tile directory is
scanned at startup, so newly prepared tiles need a server restart.

List the gaps before they bite:

```bash
java -jar terraforge-cli.jar info -c plugins/TerraForge/terraforge.yml --dem plugins/TerraForge/data/dem
```

## Mountains are flat on top

Elevation exceeded `max-y` and was soft-clamped. Either raise `terrain.max-y`, or raise
`terrain.meters-per-block` so more real metres fit into one block. See
[projection.md](projection.md#vertical-scale).

## Terrain looks smooth and featureless

At `blocks-per-km: 1.0` one block spans ~33 DEM samples, so the DEM is heavily downsampled. Raise
`blocks-per-km`, or raise `terrain.vertical-exaggeration` to make the remaining relief more visible.

## `/earth whereami` says "unknown country"

`terraforge.db` has no boundary data for that point, or the file is missing. Run
`terraforge prepare-geo`. The plugin works without it — only the geographic lookups are unavailable.

## Towny or BlueMap shows NOT INSTALLED

Expected when the plugin is absent. If it *is* installed:

1. Check the plugin name matches exactly (`Towny`, `BlueMap`).
2. Check `towny.enabled` / `bluemap.enabled` in `terraforge.yml`.
3. Check the other plugin actually enabled — TerraForge detects it at its own enable time.

## Chunk generation is slow

1. `/earth cache stats` — a hit rate below ~90% means the tile cache is too small; raise
   `cache.dem-tile-cache-entries`.
2. Check memory pressure in the same output; if it is over 1.0, caches are thrashing.
3. Pregenerate the play area (see [pregeneration.md](pregeneration.md)).
4. Profile before changing anything else — see [performance.md](performance.md).

## Server runs out of memory

DEM tiles are memory-mapped, so they consume address space rather than heap, but the caches around
them are real. Lower `cache.memory-limit-mb` and `cache.dem-tile-cache-entries`, or give the JVM
more headroom.

## New chunks do not line up with old ones

`scale`, `earth.origin`, `projection` or `terrain.*` was changed after the world was generated.
Those values are baked into existing chunks. Either revert the change or regenerate the world; there
is no migration.

## Reporting a problem

Include: TerraForge version, Paper version, `terraforge.yml`, the startup banner, and the log lines
around the failure. If it is a terrain problem, include the output of `/earth whereami` at the spot.
