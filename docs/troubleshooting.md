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

## `DIRTY BUILD` or unknown build provenance at startup

```
[TerraForge] DIRTY BUILD -- not reproducible from any commit.
```

The jar was built from a working tree with uncommitted changes, so no commit describes the code that
is running and the terrain it generates cannot be reproduced. The other variant, `Build provenance
is unknown`, means the jar records no commit at all — it was not built by Gradle from a git
checkout. Neither is fatal, and both matter before you trust a bug report or a fix: rebuild from a
clean checkout and compare the banner's `Build:` and `Jar sha256:` lines against the jar you meant
to deploy.

## The world is flat / all ocean

The DEM is not being found. Check:

1. `plugins/TerraForge/data/dem/` contains `.tfdem` files (not `.tif`).
2. The tiles cover your play area — file names encode their south-west corner, e.g. `N50E008`.
3. The log for `Missing DEM tile`.

`.tif` files are not read at runtime; run `terraforge prepare-dem` first (see [dem.md](dem.md)).

## Missing DEM tile warnings

```
[TerraForge-Generator] Missing DEM tile: N52E013 (no prepared file)
[TerraForge-Generator] Falling back to terrain.fallback-elevation (0.0 m)
```

Not fatal — that area becomes flat land at the fallback elevation. Prepare the missing tiles and
regenerate the affected chunks (already-generated chunks are not rewritten).

## Mountains are flat on top

Elevation exceeded `max-y` and was soft-clamped. Either raise `terrain.max-y`, or raise
`terrain.meters-per-block` so more real metres fit into one block. See
[projection.md](projection.md#vertical-scale).

## Terrain looks smooth and featureless

At `blocks-per-km: 1.0` one block spans ~33 DEM samples, so the DEM is heavily downsampled. Raise
`blocks-per-km`, or raise `terrain.vertical-exaggeration` to make the remaining relief more visible.

## Water or lava inside a mountain, or air inside the sea

`generation.vanilla-caves` or `generation.vanilla-decorations` is enabled in a world whose vertical
frame is not vanilla's. Those stages read vanilla's `overworld.json` — `min_y -64`, `sea_level 63`,
one metre per block — and never `terrain.*`: at `meters-per-block: 20.0` a routine 30-block vanilla
cave removes 600 m of real rock, vanilla's carvers are permitted to replace water so they breach the
seabed instead of running under it, and the aquifer then refloods the breach with water up to y=63
and with lava below y=-54. Measured in the audited world: 249,150 fluid blocks inside dry Tibetan
rock per region file, and 26.20 % of Gulf-of-Guinea ocean columns containing air.

The startup banner says which stages run, and warns when the frames disagree:

```
Caves:        TerraForge karst off, vanilla carvers ON
Decorations:  vanilla pass ON (trees, ores, springs and lava lakes together)
[TerraForge-Generator] generation.vanilla-caves/vanilla-decorations are enabled, but this
world's vertical frame (sea level 0, min y -512, 20.0 m per block) is not vanilla's (63/-64/1.0).
```

Set both keys to `false` and restart — or run the world in vanilla's frame, where those stages are
correct. Already-generated chunks keep the damage; nothing rewrites them, so the affected region has
to be regenerated. See [vertical-scale.md](vertical-scale.md) and
[configuration.md](configuration.md#generation).

## Mountain lakes generate as dry beds, or not at all

The prepared water data carries no `surface_elevation_m`, so every lake's surface altitude is
unknown, and an unknown surface means the water body is refused rather than placed — the alternative,
reading unknown as `0.0`, put every mountain lake at sea level. A database predating the column is
rejected at load:

```
prepared water data predates lake surface elevations (water_bodies has no
surface_elevation_m/bed_depth_m); re-run `terraforge prepare-geo` against this database
```

after which the plugin falls back to elevation-derived water. Re-run `terraforge prepare-geo` (or
`prepare-region`) against the database. The `.tfdem` tiles are unaffected and need no re-preparation.

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
