# Pregeneration

Generating chunks ahead of players keeps play smooth and surfaces missing data early — a coverage
gap is far better found during pregeneration than when a player walks into it.

## In-game

```
/earth pregenerate start <radius-blocks> [center-x center-z]
/earth pregenerate full confirm
/earth pregenerate pause
/earth pregenerate resume
/earth pregenerate status
/earth pregenerate cancel
```

Requires `terraforge.command.pregenerate` and a managed Earth world in state `READY` (see
[installation.md](installation.md)). `start` takes a block radius and an optional block centre
(default `0, 0`); `full confirm` pregenerates the entire configured region and requires the literal
word `confirm`. Both **create the job paused** — run `/earth pregenerate resume` to actually begin
scheduling chunks. Only one job can exist at a time; starting another while one is active is
rejected with a specific reason instead of silently queuing.

`/earth pregenerate resume` re-checks, at the moment you resume: that the checkpointed
config/DEM-data fingerprint still matches the live server (a config or data change since the job was
created blocks resume until you cancel and start a fresh job), that DEM coverage is currently
available, and that usable disk space is still above `pregeneration.minimum-free-disk-gb`.

`/earth pregenerate status` reports job state, progress (`completed`/`skipped`/`failed`/`total`/
in-flight), the region and the checkpoint age, plus live TPS, MSPT, usable disk and the configured
online-player policy.

`/earth pregenerate cancel` stops scheduling new chunks; it **never deletes chunks already
generated**.

Pregeneration runs on the generator worker pool with a bounded in-flight queue
(`pregeneration.max-in-flight`, default `1` — never unbounded), yields to player-driven chunk loads,
and auto-pauses when TPS/MSPT/disk drop below the configured thresholds or a player comes online (if
`pregeneration.pause-when-players-online` is set). Progress is checkpointed to
`plugins/TerraForge/pregeneration.json` every `pregeneration.checkpoint-every-chunks` chunks. A
restart always leaves a job that was `RUNNING` or auto-paused as `PAUSED` on load — resuming after a
restart is always a manual step, never automatic.

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

> [!NOTE]
> That last line is the CLI's own suggested command and still shows the pre-managed-world grammar.
> The current in-game command is `/earth pregenerate start <radius-blocks> [x z]`, followed by
> `/earth pregenerate resume` — see [In-game](#in-game) above.

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
3. Create the managed Earth world (`/earth world plan` then `/earth world create`, restart, then
   `/earth world verify` and `/earth doctor`) — see [installation.md](installation.md).
4. `/earth pregenerate start <small-radius>` then `/earth pregenerate resume`; inspect the terrain
   and `/earth performance`.
5. `/earth pregenerate full confirm` once disk and time are adequate.
6. Open the server to players.

## Missing data during pregeneration

Missing tiles are logged once each and filled with `terrain.fallback-elevation`. Pregeneration is
the right time to notice this: check the log for `Missing DEM tile` before letting players in, then
re-prepare and regenerate the affected area.
