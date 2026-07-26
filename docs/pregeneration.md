# Pregeneration

Generating chunks ahead of players keeps play smooth and surfaces missing data early — a coverage
gap is far better found during pregeneration than when a player walks into it.

## In-game

```
/earth pregenerate <radius>
```

Requires `terraforge.command.pregenerate`. The radius is in blocks around the world origin.

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
java -jar terraforge-cli.jar pregenerate --world earth --radius 8000
```

The CLI computes and validates the chunk set — count, disk estimate, data coverage — without writing
region files. Actual world writing is done by the server, which is the only safe writer of region
files.

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
