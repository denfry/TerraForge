# Commands

The root command is `/earth`; `/tf` and `/terraforge` are aliases. Every subcommand below tab-completes.

## Geographic (everyone)

| Command | Permission | Purpose |
|---|---|---|
| `/earth info` | `terraforge.command.info` | World, scale and projection information |
| `/earth whereami` | `terraforge.command.location` | Country, region, nearest city, lat/lon, real elevation |
| `/earth coords <lat> <lon>` | `terraforge.command.location` | Convert geographic coordinates |
| `/earth country <name>` | `terraforge.command.info` | Inspect a prepared country |
| `/earth city <name>` | `terraforge.command.info` | Inspect a prepared city |

## Operator

| Command | Permission | Purpose |
|---|---|---|
| `/earth teleport city <name>` | `terraforge.command.teleport` | Teleport to a prepared city |
| `/earth teleport country <name>` | `terraforge.command.teleport` | Teleport to a prepared country |
| `/earth cache [clear]` | `terraforge.command.cache` | Inspect or invalidate caches |
| `/earth towny refresh` | `terraforge.command.towny` | Backfill Towny town geography |
| `/earth debug [overlay]` | `terraforge.command.debug` | Inspect terrain sampling |
| `/earth reload` | `terraforge.command.reload` | Re-validate config and reload safe runtime state |

## `/earth world` — managed Earth world lifecycle

Permission: `terraforge.command.world`.

| Subcommand | Effect |
|---|---|
| `/earth world plan` | Runs every prerequisite check and reports pass/fail without writing anything |
| `/earth world create` | Re-runs the plan; if every check passes, stages the world (server.properties, bukkit.yml, paper-world.yml and the height datapack) behind a recoverable transaction. Requires a restart to take effect |
| `/earth world status` | Reports the current lifecycle state: `ABSENT`, `PENDING_RESTART`, `CREATING`, `READY` or `INVALID` |
| `/earth world verify` | Re-runs the same live verification the plugin performs on startup, on demand |
| `/earth world abort` | Deletes a staged-but-not-yet-created world. Only works while the state is `PENDING_RESTART` |

`create` never overwrites or deletes an existing `earth` world directory — staging refuses outright if
`world/earth` already exists on disk. See [Installation](installation.md#3-create-the-managed-earth-world)
for the full safe workflow and [Recovery](installation.md#recovery) for diagnosing a failed or aborted
staging.

## `/earth pregenerate` — bounded chunk pregeneration

Permission: `terraforge.command.pregenerate`. Requires the managed Earth world to be `READY`.

| Subcommand | Effect |
|---|---|
| `/earth pregenerate start <radius-blocks> [center-x center-z]` | Creates a job for the given block radius (default center `0, 0`). Starts **paused** |
| `/earth pregenerate full confirm` | Creates a job covering the entire configured region. The literal word `confirm` is required |
| `/earth pregenerate resume` | Resumes a paused job, after re-checking the config/data fingerprint and current disk headroom |
| `/earth pregenerate pause` | Pauses the active job |
| `/earth pregenerate status` | Reports job state, progress, region, checkpoint age and live server health |
| `/earth pregenerate cancel` | Cancels the job. **Never deletes already-generated chunks** — it only stops scheduling new ones |

Only one pregeneration job can exist at a time; starting a new one while a job is active is rejected.
A restart always leaves a running or auto-paused job `PAUSED` — resuming after a restart is always a
manual step, never automatic.

## `/earth data status`

Permission: `terraforge.command.data`. Reports the prepared DEM inventory: tile count, whether
bathymetry is present, coverage, background-scanned corruption count and missing requested tiles.

## `/earth doctor`

Permission: `terraforge.command.doctor`. Runs independent diagnostics and reports each with
`[ok]`/`[fail]`:

- `managed-world` — whether the managed Earth world verified against the live server this run
- `dem-data` — whether at least one DEM tile is prepared
- `boundaries` — whether the geography database loaded
- `pregeneration` — whether the pregeneration controller is active

## `/earth performance`

Permission: `terraforge.command.performance`. Reports live TPS, MSPT, online players, usable disk
space and current pregeneration state/in-flight count.

## CLI commands

| Command | Purpose |
|---|---|
| `setup` | Fetch, prepare, configure and validate a region end to end |
| `fetch` | Download the source datasets a bounding box needs |
| `init` | Create the plugin directory and a matching `terraforge.yml` |
| `prepare-region` | Prepare an existing source tree for one bounding box |
| `prepare-dem`, `prepare-landcover`, `prepare-boundaries`, `prepare-cities`, `prepare-geo` | Prepare one dataset |
| `validate` | Check a prepared database for data-quality problems |
| `info` | Show projection, scale, region extent and DEM coverage |
| `pregenerate` | Plan a bounded chunk region without writing anything |

See [`plugin.yml`](../terraforge-plugin/src/main/resources/plugin.yml) for the exact permission nodes.
