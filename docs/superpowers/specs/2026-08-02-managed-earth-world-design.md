# Managed Earth World and Safe Pregeneration

## Purpose

TerraForge will manage one real-Earth world named by `world.name` (normally `earth`) without
Multiverse. The plugin will prepare the server for first creation, provide a Paper-discovered
datapack before registries load, verify that the resulting world matches the terrain configuration,
and pregenerate chunks without building an unbounded queue or starving the server tick.

TerraForge does not manage the separate `spawn` world. Another plugin may create and operate it.
TerraForge also never deletes, replaces, or regenerates an existing world.

## Platform boundary

Managed creation requires official Paper 1.21.8 or newer within the Minecraft 1.21.x compatibility
line. It uses Paper's bootstrap and datapack-discovery lifecycle, which runs before normal plugin
enablement and registry initialization. Leaf, Purpur, Spigot, and other forks are not guaranteed to
support this feature even if the terrain generator happens to load on them.

Paper 1.21.8 cannot assign an arbitrary datapack dimension type to a world created through
`WorldCreator`. Therefore `earth` is the server's primary overworld (`level-name=earth`), and the
TerraForge datapack defines the primary overworld height. This avoids silently creating a secondary
world with vanilla height. The separate spawn world may use the same registry-level overworld
height; TerraForge does not otherwise configure or modify it.

References:

- Paper datapack discovery: <https://docs.papermc.io/paper/dev/lifecycle/datapacks/>
- Paper 1.21.8 dimension-type limitation: <https://github.com/PaperMC/Paper/issues/13111>

## Creation lifecycle

Managed creation is explicit and restart-bound. Installing the plugin alone changes no world and
does not enable the height datapack.

The persisted lifecycle is:

```text
ABSENT -> PENDING_RESTART -> CREATING -> READY
                              |           |
                              +-> INVALID <-+
```

`/earth world create` first performs a read-only preflight. It requires:

- a supported Paper runtime;
- no loaded world with the configured name;
- no existing world directory, `uid.dat`, or region data at that name;
- valid TerraForge configuration and vertical profile;
- readable prepared DEM data with usable coverage;
- writable, normalized paths confined to the server and plugin directories;
- enough usable disk space for creation and the configured safety reserve.

If preflight succeeds, the command atomically:

1. Generates the height datapack from the validated vertical profile.
2. Updates `server.properties` so `level-name` is the configured Earth world.
3. Updates `bukkit.yml` so that primary world uses generator `TerraForge`.
4. Creates a manifest-owned Earth staging directory containing only the per-world Paper settings
   and a TerraForge staging marker, so the settings apply during the first world load.
5. Creates recoverable backups of every changed server file.
6. Writes a creation manifest with state `PENDING_RESTART`, configuration fingerprint, datapack
   fingerprint, expected world name, and expected height.
7. Reports that an administrator must restart the server. TerraForge never stops it automatically.

At the next startup, the Paper bootstrap reads the manifest and validated TerraForge configuration,
regenerates or verifies the staged datapack, and registers it during datapack discovery. If the
manifest or pack is inconsistent, bootstrap fails closed before the Earth world can generate.

Paper then creates the primary Earth world and obtains TerraForge's chunk generator. The generator
returns a fixed spawn at the configured origin instead of triggering Minecraft's potentially large
safe-spawn search. Normal enablement verifies the live min/max height, loaded datapack, generator
identity, world name, vertical profile, and data fingerprint before changing the manifest to
`READY`.

`/earth world abort` is valid only in `PENDING_RESTART`, while the Earth path is either absent or is
the exact manifest-owned staging directory with no Minecraft world artifacts. It restores the
backed-up configuration files and removes only TerraForge's staged manifest, datapack, marker, and
per-world Paper settings. It never touches an existing Minecraft world.

## Command surface

### World management

```text
/earth world plan
/earth world create
/earth world status
/earth world verify
/earth world abort
```

- `plan` runs the full preflight without writing.
- `create` stages primary-world creation for the next restart.
- `status` reports `absent`, `pending-restart`, `creating`, `ready`, or `invalid` and its reason.
- `verify` compares the live world and installed data against the creation manifest.
- `abort` rolls back only a pending, not-yet-created world setup.

### Pregeneration

```text
/earth pregenerate start <radius-blocks>
/earth pregenerate start <radius-blocks> <center-x> <center-z>
/earth pregenerate full confirm
/earth pregenerate pause
/earth pregenerate resume
/earth pregenerate status
/earth pregenerate cancel
```

Radii are blocks, not chunks. `full confirm` is deliberately separate because a whole-Earth run is
millions of chunks and can consume substantial time and disk. Canceling removes the active job but
does not delete generated chunks. A later job skips chunks Paper reports as already generated.

### Diagnostics

```text
/earth data status
/earth doctor
/earth performance
```

- `data status` reports DEM count, coverage, bathymetry, corrupt files, and requested missing tiles.
- `doctor` combines runtime, datapack, manifest, world, generator, height, data, path, and disk checks.
- `performance` reports TPS, MSPT, pregeneration state, in-flight work, cache statistics, disk reserve,
  and the current automatic-pause reason.

Existing geographic, teleport, cache, Towny, debug, and reload commands remain available. Command
parsing delegates each command family to a focused handler rather than continuing to grow one class.
Every new administrator action has a `plugin.yml` permission and defaults to operators.

## Safe pregeneration architecture

The current scheduler starts one asynchronous chunk request every tick regardless of completion.
On a slow server this creates an unbounded backlog. The replacement scheduler has an explicit
bounded state machine and never has more than `max-in-flight` chunk requests active. The default is
one.

Chunks are visited in a deterministic outward spiral from the center so every intermediate result
is one contiguous playable area. Before dispatching another chunk, the controller checks:

- job state and shutdown state;
- current in-flight count;
- whether Paper already generated the chunk;
- online-player policy;
- TPS and MSPT thresholds;
- usable disk reserve;
- DEM coverage for the target chunk;
- recent generation or save failures.

Default protection values are:

```yaml
pregeneration:
  max-in-flight: 1
  pause-when-players-online: true
  minimum-tps: 18.0
  maximum-mspt: 40.0
  stable-resume-seconds: 15
  minimum-free-disk-gb: 10
  checkpoint-every-chunks: 128
```

Automatic pause uses hysteresis: work resumes only after all health conditions have remained good
for `stable-resume-seconds`. A restart restores the job in manual `PAUSED` state; an administrator
must run `resume`. Missing or corrupt DEM data stops the job by default instead of writing permanent
fallback plateaus. A future explicit policy may allow fallback, but it is not part of this feature.

Checkpoint data records the immutable job specification, traversal cursor, completed/skipped/failed
counts, pause reason, and configuration/data fingerprints. It is written atomically every configured
number of chunks and during orderly plugin shutdown. Completion callbacks update state through one
serialized controller boundary, avoiding races between Paper worker threads and the server thread.

The plugin never disables the watchdog and never raises `max-tick-time`. It addresses overload at
the source by limiting demand.

## Paper world settings

During staging, TerraForge installs only these per-world overrides into `earth/paper-world.yml`, so
they are present before Paper's first load of the Earth world:

```yaml
chunks:
  auto-save-interval: 12000
  max-auto-save-chunks-per-tick: 2
  flush-regions-on-save: false
```

Unknown and user-owned keys are preserved. TerraForge creates a backup before the first edit and
applies changes atomically. If the existing YAML cannot be safely parsed or merged, verification
reports the problem and no file is changed. Spawn-world configuration is never edited.

## Data integrity and security

Configuration, manifest content, data files, and filesystem paths are untrusted inputs.

- Resolve and normalize every target before writing.
- Require targets to remain beneath the intended server, world-container, or plugin-data root.
- Reject symlink/path traversal that escapes those roots.
- Validate identifiers and world names against Paper/Minecraft naming constraints.
- Use a temporary file in the destination directory followed by an atomic move where supported.
- Preserve permissions where practical and retain backups until the world reaches `READY`.
- Store no credentials or player data in the manifest or diagnostic output.
- Fail closed on partial configuration updates, datapack mismatch, unsupported runtime, or ambiguous
  existing world state.

## Components

The feature is split into small units with testable non-Bukkit cores:

- `WorldCreationPlan` — immutable preflight result and proposed changes.
- `ManagedWorldManifest` / store — lifecycle persistence and fingerprints.
- `ServerConfigEditor` — narrow, atomic editors for properties and YAML with rollback metadata.
- `TerraForgeDatapack` — renders and validates the pack from a vertical profile.
- Paper bootstrap class — discovers the pack only for a valid pending/ready manifest.
- `ManagedWorldService` — coordinates plan, stage, abort, startup verification, and fixed spawn.
- `PregenerationSpec` / checkpoint store — durable job definition and progress.
- `PregenerationController` — bounded dispatch and lifecycle.
- `ServerHealthPolicy` — pure decision logic for TPS/MSPT/player/disk hysteresis.
- Focused command handlers — world, pregeneration, data, and diagnostics.

Paper, filesystem, clock, disk, and chunk-loading calls sit behind narrow interfaces. Pure policy,
state transitions, traversal, validation, merge behavior, and checkpoint logic use plain JUnit 5.

## Verification strategy

Automated coverage includes:

- every legal and illegal creation-state transition;
- idempotent plan/create calls;
- refusal when any world artifact already exists;
- atomic server configuration editing and exact rollback;
- preservation of unrelated YAML/properties content;
- datapack rendering, JSON structure, fingerprints, and invalid profiles;
- fixed-spawn calculation without safe-spawn scanning;
- spiral traversal without duplicates and with deterministic resume;
- block-radius to chunk-bound conversion;
- bounded in-flight dispatch under slow and failing futures;
- skipping existing chunks;
- TPS/MSPT/player/disk pause decisions and stable-resume hysteresis;
- atomic checkpoints and paused restoration after restart;
- DEM coverage and corruption failures;
- path traversal and symlink escape rejection.

The repository-wide completion gate is `./gradlew build` (Windows: `.\gradlew.bat build`). A release
candidate also receives a clean-server smoke test on official Paper 1.21.8 covering initial install,
`plan`, `create`, restart, `verify`, small pregeneration, automatic pause, checkpoint, restart, and
resume. The smoke test confirms that no Multiverse plugin is installed and that the separate spawn
world remains untouched.

## Documentation changes

The implementation updates the README, command reference, performance guide, pregeneration guide,
vertical-scale guide, and changelog. Existing instructions to copy a datapack into a secondary
world are removed because they do not match Paper 1.21.8's registry and `WorldCreator` behavior.
