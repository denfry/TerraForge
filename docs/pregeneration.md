# Pregeneration

Generating chunks ahead of players keeps play smooth and surfaces missing data early — a coverage
gap is far better found during pregeneration than when a player walks into it.

## In-game

```
/earth pregenerate <radius-chunks>
```

Requires `terraforge.command.pregenerate`. The radius is in chunks around the issuing player's
current chunk, from `0` to `32` (at most 4,225 chunks). Only one TerraForge job can run at once;
use `/earth pregenerate status` to inspect it or `/earth pregenerate cancel` to stop scheduling new
chunks. The job requests one chunk per tick so it does not deliberately saturate the server.

Progress is reported live:

```
Preparing:
[██████████████░░░░] 72%

Chunks:
183,200 / 254,000

ETA:
00:04:37
```

Pregeneration runs on the generator worker pool, yields to player-driven chunk loads, and can be
stopped and resumed — progress is checkpointed, so a restart does not start over.

## Planning with the CLI

```bash
java -jar terraforge-cli.jar pregenerate \
    -c server/plugins/TerraForge/terraforge.yml --test-region
```

Pick the area with exactly one of `--radius <blocks>` (around the origin), `--bbox
latMin,lonMin,latMax,lonMax`, or `--test-region` (the bounds from the config). `--data` overrides
where prepared DEM tiles are looked for; by default it is `<config dir>/<data.data-directory>/dem`.

```
World:        earth
Projection:   Equirectangular -- exact north-south distance, standard parallel 51.00 degrees
Scale:        1.0 blocks/km (1000 m per block)
Origin:       51.000000, 10.000000

Blocks:       X -369..369, Z -445..445
Chunks:       X -24..23, Z -28..27 = 2,688 chunks
Region files: 4, roughly 39.4 MB on disk

DEM:          78 of 96 tile(s) prepared (81.3% of the area)
Missing tiles -- these areas would be flat at terrain.fallback-elevation:
  N47E005.tfdem
  ...
Prepare them with 'terraforge prepare-region' before generating.

Nothing was written. Run the generation in-server:
  /earth pregenerate <radius-chunks>   (stand near chunk 0, 0)
```

The command computes and validates the chunk set — count, region files, disk estimate, DEM coverage
— and **never writes anything**, which is what makes it safe to point at a live server's data
directory. Actual world writing is done by the server, the only safe writer of region files.

The coverage check is the point of the exercise: a DEM gap found here costs a second, the same gap
found by a player who walks onto a flat plateau costs a regeneration.

## Sizing

At `blocks-per-km: 1.0`, the central-europe test region is roughly 740 × 950 blocks — about 2,800
chunks, a few minutes of work. Sizes scale with the square of `blocks-per-km`:

| `blocks-per-km` | Test region chunks | Approximate region-file size |
|---|---|---|
| 0.5 | ~700 | ~10 MB |
| 1.0 | ~2,800 | ~40 MB |
| 2.0 | ~11,000 | ~160 MB |
| 5.0 | ~68,000 | ~1 GB |

## Recommended order

1. Prepare the data (`prepare-region`).
2. `terraforge info` — confirm the block extent is what you expect.
3. Pregenerate a small radius and inspect the terrain in-game.
4. Pregenerate the full region.
5. Open the server.

## Missing data during pregeneration

Missing tiles are logged once each and filled with `terrain.fallback-elevation`. Pregeneration is
the right time to notice this: check the log for `Missing DEM tile` before letting players in, then
re-prepare and regenerate the affected area.
