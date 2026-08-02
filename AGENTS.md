<!-- >>> codebase-index managed >>> -->
# codebase-index

Use the local codebase index before scanning repository files.

Skill resources: `.codex/skills/codebase-index/SKILL.md`

Run `codebase-index search "<query>" --json` for general questions, or use
`symbol`, `refs`, `impact`, and `graph` for symbol lookup, references, change
impact, and HTML graph export. Search/read commands auto-build the index when
it is missing; run `codebase-index update` when responses report stale data.
<!-- <<< codebase-index managed <<< -->

# TerraForge

Real-Earth terrain generation Paper plugin. Kotlin-free plain Java 21, Gradle multi-module. Full
contributor context lives in `docs/development.md` and `docs/architecture.md` — read those before
touching the terrain pipeline.

## Build & verify

- **`./gradlew build`** (Windows: `.\gradlew.bat build`) is the whole verification loop: compiles every
  module and runs all unit + integration tests. There is **no separate lint/typecheck task** — the
  compiler runs with `-Xlint:all,-serial,-processing -parameters`. Green `build` is the bar for done.
- JDK 21 is enforced by the toolchain; always use the wrapper, never a system Gradle.
- Focused test: `./gradlew :terraforge-core:test --tests '*Projection*'`
- Shaded jars: `:terraforge-plugin:shadowJar` and `:terraforge-cli:shadowJar` (both wired into
  `build`). Artifacts: `terraforge-plugin/build/libs/TerraForge-<version>.jar`,
  `terraforge-cli/build/libs/terraforge-cli-<version>.jar`.
- Benchmarks: `./gradlew :terraforge-benchmark:jmh` (JavaExec, not part of build).

## Versions live ONLY in gradle.properties

`minecraft_version`, `paper_version`, `java_version`, and every dependency version are declared in
`gradle.properties` — never inlined in a build script or source. `plugin.yml` gets `version` /
`api-version` via resource filtering. Retargeting Minecraft = edit `gradle.properties`, rebuild, then
fix compile errors in `BiomeMapper` (the only class touching the Minecraft biome enum).

## Architecture rules (enforced by module deps)

**"Minecraft never learns about GIS."** Dependency edges point inward; a violation is a compile error:

- `terraforge-core` — no Paper/Towny/BlueMap/SQL types; publishes to mavenLocal for third parties
- `terraforge-geo` — no Paper; owns `.tfdem` tiles, spatial index, `terraforge.db` (schema in
  `src/main/resources/schema.sql`)
- `terraforge-generator` — Paper `compileOnly`; `BiomeMapper` is the single place `ClimateBiome`
  meets `org.bukkit.block.Biome`
- `terraforge-towny` / `terraforge-bluemap` — depend only on core, APIs `compileOnly`
- `terraforge-cli` — core + geo, plus GeoTIFF/picocli (offline prep only)
- `terraforge-benchmark` — not shipped; JMH

Shaded third-party libs are relocated to `dev.terraforge.libs.*` so TerraForge never clashes with
another plugin's copies.

## Non-negotiables

- **Never generate man-made features** (roads, buildings, railways, bridges, airports, power lines).
  That is the project's purpose, not a config preference.
- Determinism is a contract: same input + config ⇒ same terrain. Noise is seeded from geographic
  position, never wall time.
- No HTTP per chunk, no GeoTIFF read at runtime, no SQL per block, no unbounded cache, no heavy GIS
  work on the Paper server thread. Providers must be thread-safe, never block on network, and return
  "no data" outside their coverage.
- Caches: implement `ManagedCache`, register with `CacheManager`, bound it. Commands: declare the
  permission in `plugin.yml`; admin ops default to `op`.
- Treat source data, config and paths as untrusted; validate and fail closed.

## Style & testing

- Plain Java 21, records for value types; comments explain **why**, not what; never swallow
  exceptions silently — log via the plugin logger with module prefixes (`[TerraForge]`,
  `[TerraForge-Generator]`, `[TerraForge-Geo]`, `[TerraForge-Towny]`, `[TerraForge-BlueMap]`).
- Pure logic → plain JUnit 5, no server. Bukkit-dependent code → extract logic, mock only the
  boundary. Every projection needs a round-trip test. Prefer small synthetic fixtures over real
  datasets.
- User-visible changes: update `CHANGELOG.md` under `Unreleased`; Conventional Commits prefixes
  welcome (`feat(generator): ...`, `fix(cli): ...`).

## Repo gotchas

- `source-data/` and `server/` are gitignored local prep dirs; the repo ships **no geodata** — never
  commit raster/vector/prepared-data or build output.
- `.github/`, `SECURITY.md`, `gradle/`, and `gradle.properties` are under maintainer review
  (CODEOWNERS) — changes there likely need discussion before a PR.
