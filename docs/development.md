# Development

## Setup

- JDK 21 (the Gradle toolchain enforces it)
- Gradle wrapper — do not use a system Gradle

```bash
./gradlew build      # compile + test everything
./gradlew test       # tests only
./gradlew :terraforge-core:test --tests '*Projection*'
```

## Versions

Platform and library versions live in `gradle.properties` **only** — never inline in a build script
or in Java source.

```properties
minecraft_version=1.21.8
paper_version=1.21.8-R0.1-SNAPSHOT
java_version=21
```

`plugin.yml` takes `version` and `api-version` from there through resource filtering.

Retargeting a new Minecraft version means editing `gradle.properties`, rebuilding, and fixing
whatever the compiler reports — mostly in `BiomeMapper`, which is the only class that touches
Minecraft's biome enum.

## Layering rules

Enforced by module dependencies; breaking one is a compile error, not a review comment:

- `terraforge-core` — no Paper, no Towny, no BlueMap, no SQL driver
- `terraforge-geo` — no Paper
- Paper types appear only in `generator`, `plugin`, `towny`, `bluemap`
- Towny and BlueMap APIs stay inside their own modules and are `compileOnly`

`dev.terraforge.plugin.world` (the managed Earth world lifecycle: plan/stage/verify/abort) and
`dev.terraforge.plugin.pregen` (bounded, checkpointed pregeneration) follow the same rule as any other
Bukkit-dependent code: the state machines, checks and health policy are plain Java, testable without a
running server, and only the thin adapters (`BukkitManagedWorldEnvironment`, `PaperPregenerationAdapter`)
touch `org.bukkit`. See [installation.md](installation.md) for the operator-facing workflow and
[commands.md](commands.md) for the command surface these packages back.

## Adding things

**A projection** — implement `Projection`, register it in `ProjectionRegistry`, document its
distortion in `docs/projection.md`. No generator changes.

**A data provider** — implement the SPI in `dev.terraforge.core.data`. It must be thread-safe, must
never block on network I/O, and must return its "no data" value outside its coverage rather than
guessing.

**A cache** — implement `ManagedCache`, register it with `CacheManager`, bound it. An unregistered
or unbounded cache will be rejected in review.

**A command** — declare the permission in `plugin.yml`; admin-only operations default to `op`.

## Testing

- pure logic (projection, scale, geodesy, config) — plain JUnit 5, no server
- Bukkit-dependent code — extract the logic into a testable class; mock only the boundary
- every projection needs a round-trip test: `toGeographic(toPlane(p)) == p`
- determinism is a contract — the same input must always give the same output

## Style

- match the surrounding code; the codebase is plain Java 21 with records for value types
- comments explain **why**, not what; the reader can read Java
- never catch an exception silently — log through the plugin logger with the module prefix
- log prefixes: `[TerraForge]`, `[TerraForge-Generator]`, `[TerraForge-Geo]`, `[TerraForge-Towny]`,
  `[TerraForge-BlueMap]`

## Phase order

The project is built in phases; each ends with a green `./gradlew build` before the next begins.
Current state is in the README's status table.

## Non-negotiable

No code path may generate roads, buildings, railways, bridges, airports, power lines or any other
man-made feature. This is not a configuration preference — it is the purpose of the project. Players
build the world; TerraForge builds the planet under it.
