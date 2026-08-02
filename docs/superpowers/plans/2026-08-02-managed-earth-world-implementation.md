# Managed Earth World and Safe Pregeneration Implementation Plan

> **For Codex:** Execute this plan task-by-task. Use test-driven development for every behavior change, run the focused test after each red/green step, and run `./gradlew.bat build` before claiming completion.

**Goal:** Let TerraForge safely stage and verify one primary Earth world named `earth`, discover its generated height datapack during Paper bootstrap, and pregenerate it through a durable one-chunk-at-a-time controller that pauses before the server overloads.

**Architecture:** Keep all state machines, validation, persistence, traversal, and health policy independent of Bukkit. The Paper-facing plugin module supplies narrow adapters for server paths, metrics, datapack discovery, commands, and async chunk loading. Managed creation is restart-bound and fail-closed: installation alone is inert, `/earth world create` writes a recoverable staging transaction, bootstrap activates only a valid manifest, and normal enablement verifies the live world before declaring it ready.

**Tech Stack:** Java 21, Gradle wrapper, Paper API 1.21.8, Jackson YAML/JSON already shaded by the project, JUnit 5, AssertJ, Mockito.

**Reference API:** Paper 1.21.8 `BootstrapContext#getDataDirectory`, `LifecycleEvents.DATAPACK_DISCOVERY`, `DatapackRegistrar#discoverPack(Path, String, Consumer)`, `Configurer#autoEnableOnServerStart`, `JavaPlugin#registerCommand`, `BasicCommand`, `Server#getTPS`, `Server#getAverageTickTime`, and `ChunkGenerator#getFixedSpawnLocation`.

---

## Task 1: Establish the baseline and shared vertical-profile model

**Files:**
- Move: `terraforge-cli/src/main/java/dev/terraforge/cli/setup/VerticalProfile.java`
- Create: `terraforge-core/src/main/java/dev/terraforge/core/config/VerticalProfile.java`
- Modify: CLI imports under `terraforge-cli/src/main/java/dev/terraforge/cli/`
- Move/Create: `terraforge-core/src/test/java/dev/terraforge/core/config/VerticalProfileTest.java`

**Step 1: Verify the untouched baseline**

Run: `./gradlew.bat build`

Expected: PASS. If it fails, record the pre-existing failure and stop before mixing it with this feature.

**Step 2: Add a failing shared-model test**

```java
@Test
void buildsProfileFromTerrainConfiguration() {
    var terrain = TerraForgeConfig.defaults().terrain();
    assertThat(VerticalProfile.from(terrain))
            .isEqualTo(new VerticalProfile(63, -64, 320, 1.0));
}
```

Run: `./gradlew.bat :terraforge-core:test --tests '*VerticalProfileTest*'`

Expected: FAIL because the core class/factory does not exist.

**Step 3: Move the record into core and add the factory**

```java
public static VerticalProfile from(TerrainSection terrain) {
    return new VerticalProfile(
            terrain.seaLevel(), terrain.minY(), terrain.maxY(), terrain.metersPerBlock());
}
```

Update CLI imports and remove the old CLI-owned source after all references compile.

**Step 4: Run focused and dependent tests**

Run: `./gradlew.bat :terraforge-core:test :terraforge-cli:test`

Expected: PASS.

**Step 5: Commit**

```text
refactor(core): share vertical world profile
```

---

## Task 2: Render a validated Paper 1.21.8 height datapack in core

**Files:**
- Create: `terraforge-core/src/main/java/dev/terraforge/core/world/TerraForgeDatapack.java`
- Create: `terraforge-core/src/test/java/dev/terraforge/core/world/TerraForgeDatapackTest.java`
- Modify: `terraforge-cli/src/main/java/dev/terraforge/cli/setup/DimensionDatapack.java`
- Modify: CLI tests that reference the legacy writer

**Step 1: Write failing renderer tests**

Cover:

- `pack.mcmeta` uses data pack format `81` for Minecraft 1.21.8;
- `overworld.json` contains `min_y`, `height`, and `logical_height` from the profile;
- invalid profiles are rejected before any file is written;
- repeated rendering is byte-identical;
- the returned SHA-256 fingerprint changes when the profile changes.

```java
var rendered = TerraForgeDatapack.render(new VerticalProfile(0, -512, 512, 20.0));
assertThat(rendered.files()).containsKeys(
        "pack.mcmeta", "data/minecraft/dimension_type/overworld.json");
assertThat(rendered.fingerprint()).matches("[0-9a-f]{64}");
```

Run: `./gradlew.bat :terraforge-core:test --tests '*TerraForgeDatapackTest*'`

Expected: FAIL.

**Step 2: Implement a pure renderer**

Use immutable `Map<String, byte[]>` output, canonical UTF-8 JSON, and SHA-256 over sorted relative paths plus bytes. Do not let the renderer accept arbitrary paths.

```java
public record RenderedPack(Map<String, byte[]> files, String fingerprint) {}

public static RenderedPack render(VerticalProfile profile) { ... }
```

**Step 3: Keep the CLI as a thin filesystem adapter**

Replace its duplicated JSON templates with calls to `TerraForgeDatapack.render(profile)`. Preserve current CLI behavior until documentation is updated later.

**Step 4: Run tests**

Run: `./gradlew.bat :terraforge-core:test :terraforge-cli:test`

Expected: PASS.

**Step 5: Commit**

```text
feat(core): render deterministic world height datapack
```

---

## Task 3: Add pregeneration configuration and validation

**Files:**
- Modify: `terraforge-core/src/main/java/dev/terraforge/core/config/TerraForgeConfig.java`
- Modify: `terraforge-core/src/main/java/dev/terraforge/core/config/ConfigValidator.java`
- Modify: `terraforge-core/src/main/resources/terraforge.yml`
- Modify: `terraforge-core/src/test/java/dev/terraforge/core/config/ConfigLoaderTest.java`
- Modify: `terraforge-core/src/test/java/dev/terraforge/core/config/ConfigValidatorTest.java`

**Step 1: Add failing default/validation tests**

```java
assertThat(config.pregeneration()).isEqualTo(new PregenerationSection(
        1, true, 18.0, 40.0, 15, 10, 128));
```

Reject `max-in-flight < 1`, TPS outside `0..20`, non-positive MSPT, negative seconds/disk, and checkpoint interval below one.

Run: `./gradlew.bat :terraforge-core:test --tests '*Config*Test*'`

Expected: FAIL.

**Step 2: Add the typed section and shipped defaults**

```java
public record PregenerationSection(
        int maxInFlight,
        boolean pauseWhenPlayersOnline,
        double minimumTps,
        double maximumMspt,
        int stableResumeSeconds,
        long minimumFreeDiskGb,
        int checkpointEveryChunks) {}
```

**Step 3: Run focused tests**

Run: `./gradlew.bat :terraforge-core:test --tests '*Config*Test*'`

Expected: PASS.

**Step 4: Commit**

```text
feat(config): add safe pregeneration limits
```

---

## Task 4: Implement secure atomic storage and managed-world manifest

**Files:**
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/world/ManagedWorldState.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/world/ManagedWorldManifest.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/world/ManagedWorldManifestStore.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/io/SafePathResolver.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/io/AtomicFileWriter.java`
- Create: `terraforge-plugin/src/test/java/dev/terraforge/plugin/world/ManagedWorldManifestStoreTest.java`
- Create: `terraforge-plugin/src/test/java/dev/terraforge/plugin/io/SafePathResolverTest.java`

**Step 1: Write failing state and path-security tests**

Cover all legal transitions:

```text
ABSENT -> PENDING_RESTART -> CREATING -> READY
PENDING_RESTART|CREATING|READY -> INVALID
```

Reject every other transition. Test `..`, absolute foreign paths, and a symlink under the allowed root that resolves outside it. Test a truncated temporary manifest never replaces the good manifest.

Run: `./gradlew.bat :terraforge-plugin:test --tests '*ManagedWorldManifestStoreTest' --tests '*SafePathResolverTest'`

Expected: FAIL.

**Step 2: Implement immutable manifest data**

```java
public record ManagedWorldManifest(
        int schemaVersion,
        ManagedWorldState state,
        String worldName,
        int minY,
        int maxY,
        String configFingerprint,
        String dataFingerprint,
        String datapackFingerprint,
        List<String> ownedStagingFiles,
        String reason) {}
```

Persist at `plugins/TerraForge/managed-world.json`. Deserialize with unknown fields rejected, validate schema/version/name/fingerprints, write beside the target, fsync where practical, then atomic-move with a same-filesystem fallback.

**Step 3: Implement root confinement**

Resolve and normalize first, compare real parent paths, reject symlink escapes, and require the target to remain below the exact server/plugin/world-container root supplied by the caller.

**Step 4: Run focused tests**

Run: `./gradlew.bat :terraforge-plugin:test --tests '*ManagedWorldManifestStoreTest' --tests '*SafePathResolverTest'`

Expected: PASS.

**Step 5: Commit**

```text
feat(plugin): persist managed world lifecycle safely
```

---

## Task 5: Build reversible server configuration editors

**Files:**
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/world/ServerPropertiesEditor.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/world/BukkitWorldsEditor.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/world/PaperWorldSettingsEditor.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/world/WorldStagingTransaction.java`
- Create: matching tests under `terraforge-plugin/src/test/java/dev/terraforge/plugin/world/`

**Step 1: Write failing preservation and rollback tests**

Fixtures must include comments, unrelated keys, CRLF and LF variants, an existing `worlds.spawn` section, malformed YAML, and simulated failure after each staged write.

Assertions:

- only `level-name=earth` changes in `server.properties`;
- only `worlds.earth.generator=TerraForge` is merged in `bukkit.yml`;
- `worlds.spawn` is byte/semantic equivalent and never selected as a target;
- `earth/paper-world.yml` receives only the three approved `chunks` values;
- malformed input produces no write;
- rollback restores exact original bytes;
- backups are retained until `READY`.

Run: `./gradlew.bat :terraforge-plugin:test --tests '*EditorTest' --tests '*WorldStagingTransactionTest'`

Expected: FAIL.

**Step 2: Implement plan-then-commit editors**

Each editor returns a proposed byte array and change description without writing. `WorldStagingTransaction` validates every proposal and target first, writes backups, atomically applies changes, then writes the manifest last. On any error, restore exact backups in reverse order.

```java
public interface PlannedEdit {
    Path target();
    byte[] original();
    byte[] replacement();
}
```

Use Jackson YAML trees for semantic merge and explicitly copy all unknown nodes. Do not follow aliases or accept custom YAML types.

**Step 3: Implement strict abort ownership**

Abort only when manifest state is `PENDING_RESTART` and the Earth directory contains exactly the marker and manifest-listed staged files. Refuse if `level.dat`, `uid.dat`, `region/`, `entities/`, `poi/`, or any unknown entry exists.

**Step 4: Run focused tests**

Run: `./gradlew.bat :terraforge-plugin:test --tests '*EditorTest' --tests '*WorldStagingTransactionTest'`

Expected: PASS.

**Step 5: Commit**

```text
feat(plugin): stage primary Earth world transaction
```

---

## Task 6: Add read-only preflight and managed-world orchestration

**Files:**
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/world/WorldCreationCheck.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/world/WorldCreationPlan.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/world/ManagedWorldEnvironment.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/world/ManagedWorldService.java`
- Create: tests under `terraforge-plugin/src/test/java/dev/terraforge/plugin/world/`

**Step 1: Write failing preflight tests**

Cover supported official Paper, configured name `earth`, loaded-world collision, any pre-existing directory/artifact, invalid DEM directory, missing/corrupt prepared tiles, unwritable roots, insufficient usable space, and a completely clean plan. Assert `plan()` performs zero writes and repeated `stage()` is idempotent only for an identical pending manifest.

Run: `./gradlew.bat :terraforge-plugin:test --tests '*WorldCreationPlanTest' --tests '*ManagedWorldServiceTest'`

Expected: FAIL.

**Step 2: Implement environment ports and plan aggregation**

```java
public interface ManagedWorldEnvironment {
    boolean isOfficialSupportedPaper();
    boolean isWorldLoaded(String name);
    Path serverRoot();
    Path worldContainer();
    long usableDiskBytes(Path path) throws IOException;
}
```

Return all checks, not just the first failure, so `/earth world plan` and `/earth doctor` are useful. A plan is executable only when every required check passes.

**Step 3: Connect plan, stage, abort, and status**

`ManagedWorldService` coordinates the renderer, editors, transaction, manifest store, and DEM validation. It must never call `WorldCreator`, delete a real world, edit `spawn`, stop the server, or modify watchdog settings.

**Step 4: Run focused tests**

Run: `./gradlew.bat :terraforge-plugin:test --tests '*WorldCreationPlanTest' --tests '*ManagedWorldServiceTest'`

Expected: PASS.

**Step 5: Commit**

```text
feat(plugin): plan and stage managed Earth creation
```

---

## Task 7: Convert to a Paper plugin and discover the staged datapack at bootstrap

**Files:**
- Create: `terraforge-plugin/src/main/resources/paper-plugin.yml`
- Modify: `terraforge-plugin/src/main/resources/plugin.yml`
- Modify: `terraforge-plugin/build.gradle.kts`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/TerraForgeBootstrap.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/world/BootstrapDatapackService.java`
- Create: `terraforge-plugin/src/test/java/dev/terraforge/plugin/world/BootstrapDatapackServiceTest.java`

**Step 1: Add failing bootstrap policy tests**

Test that no manifest means no discovery; `PENDING_RESTART`, `CREATING`, and `READY` discover only a pack whose fingerprint/profile matches; `INVALID`, malformed, foreign path, missing pack, and fingerprint mismatch fail closed with an actionable exception.

Run: `./gradlew.bat :terraforge-plugin:test --tests '*BootstrapDatapackServiceTest'`

Expected: FAIL.

**Step 2: Add Paper descriptor and resource filtering**

```yaml
name: TerraForge
version: '${version}'
main: dev.terraforge.plugin.TerraForgePlugin
bootstrapper: dev.terraforge.plugin.TerraForgeBootstrap
api-version: '${apiVersion}'
dependencies:
  server:
    Towny: {load: BEFORE, required: false, join-classpath: true}
    NewTowny: {load: BEFORE, required: false, join-classpath: true}
    BlueMap: {load: BEFORE, required: false, join-classpath: true}
```

Expand both descriptor files in `processResources`. Keep `plugin.yml` only for legacy metadata/permissions during migration; do not depend on its command section because Paper plugins ignore it.

**Step 3: Register the datapack lifecycle handler**

```java
context.getLifecycleManager().registerEventHandler(
        LifecycleEvents.DATAPACK_DISCOVERY,
        event -> service.discover(context.getDataDirectory(), event.registrar()));
```

The adapter calls:

```java
registrar.discoverPack(packPath, "earth-height",
        configurer -> configurer.autoEnableOnServerStart(true));
```

Always rediscover it on every lifecycle event while the valid manifest remains pending/creating/ready.

**Step 4: Verify the shaded jar contains both descriptors and bootstrap class**

Run: `./gradlew.bat :terraforge-plugin:shadowJar`

Then inspect: `jar tf terraforge-plugin/build/libs/TerraForge-*.jar`

Expected: `paper-plugin.yml`, `plugin.yml`, and `TerraForgeBootstrap.class` appear once.

**Step 5: Commit**

```text
feat(plugin): discover Earth datapack during Paper bootstrap
```

---

## Task 8: Verify startup and bypass random safe-spawn scanning

**Files:**
- Modify: `terraforge-generator/src/main/java/dev/terraforge/generator/TerraForgeChunkGenerator.java`
- Create: `terraforge-generator/src/test/java/dev/terraforge/generator/FixedSpawnTest.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/world/LiveWorldVerifier.java`
- Create: `terraforge-plugin/src/test/java/dev/terraforge/plugin/world/LiveWorldVerifierTest.java`
- Modify: `terraforge-plugin/src/main/java/dev/terraforge/plugin/TerraForgePlugin.java`

**Step 1: Add failing fixed-spawn and live verification tests**

Assert fixed spawn is `(0.5, safeSurfaceY, 0.5)` in the target world and does not use the supplied random. Verify world name, primary-world identity, generator identity, min/max height, enabled datapack name, config/data/datapack fingerprints, and exact manifest state transitions.

Run: `./gradlew.bat :terraforge-generator:test --tests '*FixedSpawnTest' :terraforge-plugin:test --tests '*LiveWorldVerifierTest'`

Expected: FAIL.

**Step 2: Add the generator hook**

```java
@Override
public Location getFixedSpawnLocation(World world, Random random) {
    int y = Math.clamp(getBaseHeight(world, random, 0, 0, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1,
            world.getMinHeight() + 1, world.getMaxHeight() - 1);
    return new Location(world, 0.5, y, 0.5);
}
```

If mocking `WorldInfo` reveals API mismatch, extract the pure Y clamp and mock only the Bukkit boundary.

**Step 3: Verify datapack and world on enable**

Use `Server#getDatapackManager().getPack("TerraForge/earth-height")` and require `isEnabled()`. Mark `CREATING` before live checks and `READY` only after every check succeeds. On mismatch, write `INVALID`, log every failed check, disable managed operations, and do not generate additional chunks.

**Step 4: Run focused tests**

Run: `./gradlew.bat :terraforge-generator:test --tests '*FixedSpawnTest' :terraforge-plugin:test --tests '*LiveWorldVerifierTest'`

Expected: PASS.

**Step 5: Commit**

```text
feat(generator): fix and verify managed Earth spawn
```

---

## Task 9: Refactor command routing and expose managed-world commands

**Files:**
- Modify: `terraforge-plugin/src/main/java/dev/terraforge/plugin/EarthCommand.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/command/PaperEarthCommand.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/command/EarthCommandRouter.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/command/WorldCommandHandler.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/command/CommandResult.java`
- Create: tests under `terraforge-plugin/src/test/java/dev/terraforge/plugin/command/`
- Modify: `terraforge-plugin/src/main/java/dev/terraforge/plugin/TerraForgePlugin.java`
- Modify: `terraforge-plugin/src/main/resources/plugin.yml`

**Step 1: Add failing parser/permission/output tests**

Test `plan/create/status/verify/abort`, missing args, extra args, tab suggestions, permission denial, console use, idempotent status, and error sanitization. Handlers return structured messages; tests must not need a running server.

Run: `./gradlew.bat :terraforge-plugin:test --tests '*Command*Test'`

Expected: FAIL.

**Step 2: Implement focused routing**

```java
public interface EarthSubcommand {
    CommandResult execute(CommandSender sender, List<String> args);
    List<String> suggest(CommandSender sender, List<String> args);
}
```

Retain geographic behavior while moving each new family behind a handler. Avoid exposing stack traces or filesystem paths to non-console senders.

**Step 3: Adapt to Paper `BasicCommand` and dynamic registration**

```java
registerCommand("earth", "TerraForge geographic and Earth-world commands",
        List.of("tf", "terraforge"), new PaperEarthCommand(router));
```

`PaperEarthCommand` reads the sender from `CommandSourceStack#getSender`, delegates execute/suggest, and declares root permission only if it does not block public read-only geographic commands. Register new operator-default nodes for world, diagnostic, and pregeneration administration.

**Step 4: Run tests**

Run: `./gradlew.bat :terraforge-plugin:test --tests '*Command*Test'`

Expected: PASS.

**Step 5: Commit**

```text
refactor(plugin): route Paper Earth commands by feature
```

---

## Task 10: Model deterministic pregeneration jobs and checkpoints

**Files:**
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/pregen/PregenerationSpec.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/pregen/PregenerationState.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/pregen/SpiralCursor.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/pregen/PregenerationCheckpoint.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/pregen/PregenerationCheckpointStore.java`
- Create: matching tests under `terraforge-plugin/src/test/java/dev/terraforge/plugin/pregen/`

**Step 1: Write failing geometry/state tests**

Cover block radius conversion, boundary chunks, zero radius, overflow/negative rejection, no duplicate spiral coordinates, deterministic prefix/resume, full-Earth bounds derived from the active projection, legal job transitions, and restart restoration to manual `PAUSED`.

```java
assertThat(SpiralCursor.from(0).next(9)).containsExactly(
        chunk(0, 0), chunk(1, 0), chunk(1, 1), chunk(0, 1), chunk(-1, 1),
        chunk(-1, 0), chunk(-1, -1), chunk(0, -1), chunk(1, -1));
```

Run: `./gradlew.bat :terraforge-plugin:test --tests '*PregenerationSpecTest' --tests '*SpiralCursorTest' --tests '*Checkpoint*Test'`

Expected: FAIL.

**Step 2: Implement immutable specification and cursor**

Store center/radius in blocks and derived inclusive chunk bounds. Cursor progress is an ordinal, making checkpoint resume deterministic without serializing a huge coordinate list.

**Step 3: Implement atomic checkpoint persistence**

Persist at `plugins/TerraForge/pregeneration.json` with schema, immutable spec, cursor, counts, state, pause reason, config fingerprint, and data fingerprint. On load, change `RUNNING`/`AUTO_PAUSED` to `PAUSED` with reason `restart-required-manual-resume`.

**Step 4: Run focused tests**

Run: `./gradlew.bat :terraforge-plugin:test --tests '*PregenerationSpecTest' --tests '*SpiralCursorTest' --tests '*Checkpoint*Test'`

Expected: PASS.

**Step 5: Commit**

```text
feat(plugin): persist deterministic pregeneration jobs
```

---

## Task 11: Implement health hysteresis and bounded dispatch

**Files:**
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/pregen/ServerHealthSnapshot.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/pregen/ServerHealthPolicy.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/pregen/ChunkGenerationPort.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/pregen/PregenerationController.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/pregen/PaperPregenerationAdapter.java`
- Delete: `terraforge-plugin/src/main/java/dev/terraforge/plugin/PregenerationJob.java`
- Create: matching tests under `terraforge-plugin/src/test/java/dev/terraforge/plugin/pregen/`

**Step 1: Write failing health-policy tests**

Test pause priority and messages for players online, TPS below threshold, MSPT above threshold, disk below reserve, missing DEM coverage, recent chunk/save failure, and shutdown. With a fake clock, verify a good server must remain good for exactly `stable-resume-seconds` before dispatch resumes and any bad sample resets the window.

Run: `./gradlew.bat :terraforge-plugin:test --tests '*ServerHealthPolicyTest'`

Expected: FAIL.

**Step 2: Implement the pure policy**

```java
public record HealthDecision(boolean mayDispatch, String reason) {}

public HealthDecision evaluate(ServerHealthSnapshot snapshot, Instant now) { ... }
```

Use Paper's one-minute TPS (`getTPS()[0]`) and average MSPT (`getAverageTickTime`) only in the adapter, not the policy.

**Step 3: Write failing controller tests**

Use controllable futures to prove:

- in-flight never exceeds configured maximum, including slow futures;
- default max is one;
- no next chunk starts until completion at max one;
- already-generated chunks increment skipped without loading;
- callbacks serialize onto one controller executor/scheduler boundary;
- pause/resume/cancel/status are race-safe;
- exceptions increment failure count, checkpoint, and pause;
- missing DEM stops before permanent fallback terrain is generated;
- checkpoint writes every N terminal chunks and on shutdown;
- cancel removes job state but does not delete world chunks.

Run: `./gradlew.bat :terraforge-plugin:test --tests '*PregenerationControllerTest'`

Expected: FAIL.

**Step 4: Implement bounded controller and Paper adapter**

Call `World#isChunkGenerated` before `World#getChunkAtAsync(x, z, true)`. Never schedule work merely because a tick occurred; a dispatch slot opens only after the prior future completes and the serialized controller processes that completion. Store no `CommandSender` in durable state.

**Step 5: Run focused tests**

Run: `./gradlew.bat :terraforge-plugin:test --tests '*ServerHealthPolicyTest' --tests '*PregenerationControllerTest'`

Expected: PASS.

**Step 6: Commit**

```text
perf(plugin): bound and throttle Earth pregeneration
```

---

## Task 12: Expose pregeneration and diagnostics commands

**Files:**
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/command/PregenerationCommandHandler.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/command/DataCommandHandler.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/command/DoctorCommandHandler.java`
- Create: `terraforge-plugin/src/main/java/dev/terraforge/plugin/command/PerformanceCommandHandler.java`
- Modify: `terraforge-plugin/src/main/java/dev/terraforge/plugin/command/EarthCommandRouter.java`
- Modify: `terraforge-plugin/src/main/resources/plugin.yml`
- Create/Modify: command tests

**Step 1: Add failing command tests**

Cover exact grammar:

```text
/earth pregenerate start <radius-blocks> [center-x center-z]
/earth pregenerate full confirm
/earth pregenerate pause|resume|status|cancel
/earth data status
/earth doctor
/earth performance
```

Reject `full` without literal `confirm`, non-numeric/negative/overflow radii, starts outside the managed ready Earth world, duplicate active jobs, config/data fingerprint mismatch, and resume when disk/DEM checks fail.

Run: `./gradlew.bat :terraforge-plugin:test --tests '*PregenerationCommandHandlerTest' --tests '*Diagnostic*Test'`

Expected: FAIL.

**Step 2: Implement command output**

Status includes state, completed/skipped/failed/total, in-flight, center/radius, checkpoint age, TPS, MSPT, usable disk, reserve, online-player policy, and current automatic-pause reason. `data status` reports tile count/coverage/corruption/requested missing tiles without scanning every tile synchronously on the server thread; use cached startup inventory or an async refresh.

**Step 3: Add lifecycle integration**

On enable, load checkpoint as paused only after managed-world verification succeeds. On disable, stop dispatch, serialize final state, and wait only for the short bounded checkpoint write—not for chunk generation futures.

**Step 4: Run plugin tests**

Run: `./gradlew.bat :terraforge-plugin:test`

Expected: PASS.

**Step 5: Commit**

```text
feat(plugin): add Earth lifecycle diagnostics and controls
```

---

## Task 13: Update operator documentation and changelog

**Files:**
- Modify: `README.md`
- Modify: `docs/commands.md`
- Modify: `docs/configuration.md`
- Modify: `docs/performance.md`
- Modify: `docs/pregeneration.md`
- Modify: `docs/vertical-scale.md`
- Modify: `docs/development.md`
- Modify: `CHANGELOG.md`

**Step 1: Remove obsolete workflow**

Remove instructions that tell operators to create the Earth world through Multiverse or manually copy a height datapack into a secondary world. State the official Paper 1.21.8+ boundary and explain that `earth` must be primary due to Paper's dimension-type limitation.

**Step 2: Document exact safe workflow**

```text
1. Install TerraForge on a stopped/clean Paper server.
2. Start once and prepare/import data.
3. Run /earth world plan.
4. Run /earth world create only after every check passes.
5. Restart from the hosting panel.
6. Run /earth world verify and /earth doctor.
7. Start a small block-radius pregeneration; inspect /earth performance.
8. Use /earth pregenerate full confirm only with adequate disk/time.
```

Explicitly state that `spawn` is owned by another plugin and TerraForge does not edit it, cancellation never deletes chunks, restart restores pregen paused, watchdog settings are not changed, and backups remain until ready.

**Step 3: Document recovery**

Explain `world abort` only before first creation, `INVALID` diagnostics, where manifest/checkpoint/backups live, and why an existing `earth` directory must be moved/renamed manually rather than deleted by TerraForge.

**Step 4: Commit**

```text
docs: replace Multiverse Earth setup workflow
```

---

## Task 14: Full verification and clean Paper smoke test

**Files:**
- Create if useful: `docs/testing/managed-earth-smoke-test.md`
- Modify only source/tests needed to fix discovered defects

**Step 1: Run the repository gate**

Run: `./gradlew.bat build`

Expected: PASS with every module compiled, unit/integration tests green, and both shaded jars produced.

**Step 2: Inspect artifacts**

Run:

```powershell
Get-ChildItem terraforge-plugin\build\libs\TerraForge-*.jar
jar tf (Get-ChildItem terraforge-plugin\build\libs\TerraForge-*.jar | Select-Object -First 1).FullName
```

Expected: one shaded plugin artifact containing both plugin descriptors, bootstrap class, core renderer, manifest classes, and no duplicate descriptor entries.

**Step 3: Run clean-server smoke test on official Paper 1.21.8**

Use a disposable server directory, never the user's live host. Confirm:

- first install leaves `level-name` and worlds untouched;
- `world plan` reports all failures without writes;
- after prepared DEM is installed, `world create` backs up and stages only approved files;
- restart discovers/enables `TerraForge/earth-height`, creates primary `earth`, and avoids a long random-spawn scan;
- `world verify` reports correct generator and height;
- no Multiverse plugin is installed;
- a separate `spawn` world/config remains unchanged;
- a small pregen never exceeds one in-flight future;
- joining a player or forcing high MSPT auto-pauses it;
- good health for 15 seconds permits resume;
- restart restores the job manually paused and `/earth pregenerate resume` continues the same spiral;
- stopping the server produces no watchdog dump and no corrupt checkpoint.

**Step 4: Security/self-review**

Review every filesystem input-to-write boundary, manifest/YAML/JSON parser, permission check, async callback, and error message. Confirm no traversal, symlink escape, overwrite/delete of an existing world, secret/path leakage to players, unbounded queue, server-thread GIS scan, or spawn-world mutation.

**Step 5: Re-run the gate after smoke-test fixes**

Run: `./gradlew.bat build`

Expected: PASS.

**Step 6: Commit**

```text
test(plugin): cover managed Earth lifecycle smoke path
```

---

## Completion checklist

- `git status --short` contains no unintended files; preserve the user's pre-existing change in `terraforge-geo/src/main/java/dev/terraforge/geo/dem/FileDemReader.java` and never stage it.
- `./gradlew.bat build` passes from a clean implementation state.
- Installing the jar is inert until `/earth world create`.
- An existing `earth` world is always refused and never overwritten/deleted.
- Bootstrap discovers the pack only from a valid manifest.
- Live world verification must pass before managed state becomes `READY`.
- Pregeneration has a hard in-flight bound and durable manual-pause recovery.
- `spawn` and watchdog configuration are untouched.
- `CHANGELOG.md` documents the user-visible behavior.
