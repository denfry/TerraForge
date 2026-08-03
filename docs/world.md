# Preparing the whole planet

Everything below is the ordinary `setup` flow with one flag. This page exists because a planet is
not a large region — it is a different order of magnitude in download, in disk and in the time the
preparation takes, and those numbers are worth seeing before you start rather than six hours in.

```bash
java -jar terraforge-cli.jar setup --whole-world \
    --region-name world \
    -o ./server/plugins/TerraForge \
    -i ./source-data
```

`--whole-world` is exactly `--lat-min -90 --lat-max 90 --lon-min -180 --lon-max 180`; it exists so
the four edges cannot be mistyped. The origin becomes 0°, 0° — the intersection of the equator and
the prime meridian, at block (0, 0) — and `test-region` becomes the planet.

> [!IMPORTANT]
> Run it with `--dry-run` first. It reports the exact download size for your options and stops.

## What it costs

At the default `blocks-per-km: 1.0` the world is about 40,075 blocks across and 20,000 tall, well
inside Minecraft's ±30,000,000 limit. The cost is data, not coordinates.

| | Files published | Download | Prepared on disk |
|---|---|---|---|
| DEM, GLO-90 (default) | ~27,500 | ~75 GB | ~75 GB |
| DEM, GLO-30 (`--dem-resolution 30`) | ~25,600 | ~555 GB | ~660 GB |
| Land cover, ESA WorldCover | ~2,800 | ~140 GB | ~6 GB |
| Boundaries, lakes, gazetteer | 4 | 68 MB | 52 MB |

The DEM row counts one-degree cells and the land-cover row three-degree ones, which is why there
are ten times fewer of the latter. Both are published only where there is land, so roughly 40% of
the grid exists at all; a 404 on an ocean-only cell is reported as "not published" and is not a
failure. The vector row is the whole planet either way: 238 countries, 4,548 regions, 34,065 cities
and 1,124 lakes.

Only the DEM is mandatory. The three ways to make this smaller, in the order worth trying:

| Option | Effect |
|---|---|
| `--skip landcover` | Removes 140 GB — the largest download — outright. Biomes fall back to a climate estimate from latitude, elevation and water: coarser, and a perfectly reasonable planet. |
| `--samples-per-degree 300` | Quarters the prepared land cover (~1.5 GB) without changing the download. At 1 block/km, 370 m per sample is still finer than a block. |
| Prepare continents separately | Same commands, one box at a time, into the same output directory. See below. |

The gazetteer and boundaries are the whole planet regardless of the box, so a global database costs
52 MB whatever else you skip. Take it: `/earth whereami`, `/earth city` and the BlueMap markers are
the cheapest part of the world by a factor of a thousand.

## How long it takes

Three separate waits, in order:

1. **Download.** ~65,000 DEM tiles and ~5,800 land-cover tiles. `--parallel` (default 6, maximum
   16) sets how many transfer at once; the limit is round-trip latency per file, not bandwidth.
   Plan for hours, and know that it resumes.
2. **Transcode.** CPU-bound and parallel across `--threads` (default: one per core, at most 8).
   This is the long pole for the DEM — budget several hours on eight cores.
3. **Vector import.** Minutes. One transaction, so it is all-or-nothing.

Nothing here has to finish in one sitting. The source directory is a cache and prepared tiles are
skipped when they already exist, so an interrupted run is resumed by re-running the same command.
A tile that fails is counted, named and stepped over; the summary says what is missing.

### One continent at a time

The output directory is additive — prepared tiles are named after their degree cell, so two runs
into the same directory produce one world, not two. What must not change between runs is the
configuration, because `earth.origin` decides where everything lands:

```bash
# Write the configuration once, for the whole planet.
java -jar terraforge-cli.jar init --whole-world --region-name world -o ./server/plugins/TerraForge

# Then fill it in, in any order, over as many sessions as you like.
java -jar terraforge-cli.jar setup --lat-min 35 --lat-max 71 --lon-min -25 --lon-max 45 \
    -o ./server/plugins/TerraForge          # Europe
java -jar terraforge-cli.jar setup --lat-min -35 --lat-max 38 --lon-min -20 --lon-max 55 \
    -o ./server/plugins/TerraForge          # Africa
```

`setup` keeps an existing `terraforge.yml` rather than rewriting it, and warns if the box being
prepared does not overlap the configured region — so the second command cannot silently move the
world out from under the first.

A cell with no prepared DEM is not an error at any point: it falls back to
`terrain.fallback-elevation` and is logged once. A half-prepared planet is a working server with
flat oceans where the data has not arrived yet.

## What the server needs

Nothing changes about the runtime except the amount of disk it reads from:

| | Test region | Whole planet |
|---|---|---|
| Prepared data on disk | ~1 GB | ~85 GB |
| Resident DEM tiles | up to 256 | up to 256 |
| Resident land-cover grids | up to 256 | up to 256 |
| Heap for geodata | ~100 MB | ~100 MB |
| Startup catalogue scan | < 1 s | ~30 s |

Tiles and grids are read on demand and evicted by the cache bounds in `cache`, so the resident set
follows where players are, not how much of the planet is prepared. The startup scan reads a few
dozen header bytes per file — it is the one place a planet is measurably slower to start than a
region, and it happens once.

Put the prepared data on an SSD. The generator's access pattern is random reads across tens of
thousands of files, which is the workload spinning disks are worst at.

## Pregenerating

Do not pregenerate a planet. At 1 block/km the world is ~2,500,000 chunks; generating all of them
would take weeks and produce hundreds of gigabytes of region files that nobody will ever visit.
Pregenerate the areas players actually start in:

```
/earth teleport city Berlin
/earth pregenerate start 500
/earth pregenerate resume
```

Everything else generates when somebody goes there, which is what the deterministic pipeline is for:
a chunk generated next year from the same data is identical to one generated today.
