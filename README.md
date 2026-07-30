<p align="center">
  <img src="docs/assets/terraforge-banner.png" alt="A real-Earth landscape transformed into natural voxel terrain" width="1200">
</p>

<h1 align="center">TerraForge</h1>

<p align="center">
  <strong>Real Earth terrain generation for Paper — natural geography in, player-built history out.</strong>
</p>

<p align="center">
  <a href="https://github.com/denfry/TerraForge/actions/workflows/build.yml"><img alt="Build" src="https://github.com/denfry/TerraForge/actions/workflows/build.yml/badge.svg"></a>
  <a href="https://github.com/denfry/TerraForge/releases"><img alt="Latest release" src="https://img.shields.io/github/v/release/denfry/TerraForge?include_prereleases&sort=semver"></a>
  <a href="https://github.com/denfry/TerraForge/blob/main/LICENSE"><img alt="MIT License" src="https://img.shields.io/github/license/denfry/TerraForge"></a>
  <a href="https://github.com/denfry/TerraForge/wiki"><img alt="Wiki" src="https://img.shields.io/badge/docs-wiki-blue"></a>
  <a href="https://github.com/denfry/TerraForge/discussions"><img alt="Discussions" src="https://img.shields.io/badge/chat-discussions-blueviolet"></a>
  <img alt="Paper 1.21.8" src="https://img.shields.io/badge/Paper-1.21.8-222?logo=papermc">
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk">
</p>

TerraForge is an open-source Paper plugin that turns prepared real-world elevation, coastlines,
water and land-cover data into deterministic Minecraft terrain. It generates the planet beneath the
players; roads, buildings, railways and every other man-made feature are deliberately out of scope.

> [!IMPORTANT]
> TerraForge is currently **0.1.x preview software**. Test it on a new world, keep backups, and read
> the release notes before upgrading. Changes to projection, scale or terrain settings can create
> permanent seams between old and new chunks.

```text
REAL EARTH DATA → TerraForge CLI → natural Minecraft terrain → players build → Towny governs → BlueMap displays
```

## Why TerraForge?

- **Real relief** — mountains, valleys and coastlines come from prepared DEM data.
- **Natural biomes** — land cover, latitude, elevation and water state drive biome selection.
- **No runtime downloads** — production servers only read local, prepared data.
- **Deterministic generation** — the same configuration and source data produce the same world.
- **Conservative fallbacks** — missing coverage degrades visibly and safely instead of inventing data.
- **Optional integrations** — Towny/NewTowny and BlueMap are detected at runtime.
- **Operator tooling** — an offline CLI prepares, validates and inspects regional datasets.

| Generated from real data | Never generated |
|---|---|
| Continents, coastlines and islands | Roads, highways and streets |
| Ocean depth, lakes and natural rivers | Buildings, villages and structures |
| Mountains, valleys, plains and deserts | Railways, bridges and airports |
| Biomes from real land cover | Power lines or industrial areas |

`generation.natural-only: true` is the default. TerraForge fails closed if an infrastructure switch
is enabled, and the generator contains no code path that places man-made structures.

## Requirements

| Component | Version |
|---|---|
| Server | Paper 1.21.8 |
| Runtime | Java 21 |
| TerraForge data | Prepared locally with the matching CLI release |
| Optional | Towny/NewTowny, BlueMap |

Exact supported versions are defined in [`gradle.properties`](gradle.properties). Paper forks may
work, but the public compatibility target is Paper.

## Quick start

1. Download the plugin and CLI jars from the
   [latest GitHub release](https://github.com/denfry/TerraForge/releases/latest).
2. Put `TerraForge-<version>.jar` in the server's `plugins/` directory and start the server once.
3. Prepare a bounded test region offline:

```bash
java -jar terraforge-cli-<version>.jar prepare-region \
    --lat-min 47.0 --lat-max 55.5 --lon-min 5.0 --lon-max 15.5 \
    -i ./source-data \
    -o ./server/plugins/TerraForge
```

4. Register the generator in `bukkit.yml`:

```yaml
worlds:
  earth:
    generator: TerraForge
```

5. Start Paper and verify the setup:

```text
/earth info
/earth whereami
```

TerraForge does not bundle or download geodata. The complete workflow, expected source layout and
world-creation options are documented in the
[installation guide](docs/installation.md) and [data-source guide](DATA_SOURCES.md).

## Commands

The root command is `/earth`; `/tf` and `/terraforge` are aliases.

| Command | Purpose | Default permission |
|---|---|---|
| `/earth info` | Show world, scale and projection information | Everyone |
| `/earth whereami` | Show the current real-world location | Everyone |
| `/earth coords <lat> <lon>` | Convert geographic coordinates | Everyone |
| `/earth distance <lat> <lon>` | Measure geodesic distance | Everyone |
| `/earth country <name>` | Inspect a country | Everyone |
| `/earth city <name>` | Inspect a city | Everyone |
| `/earth teleport city <name>` | Teleport to a prepared city | Operators |
| `/earth teleport country <name>` | Teleport to a prepared country | Operators |
| `/earth pregenerate <radius>` | Generate a bounded chunk region | Operators |
| `/earth cache [clear]` | Inspect or invalidate caches | Operators |
| `/earth towny refresh` | Backfill Towny geography | Operators |
| `/earth debug [overlay]` | Inspect terrain sampling | Operators |
| `/earth reload` | Validate and reload safe runtime state | Operators |

See [`plugin.yml`](terraforge-plugin/src/main/resources/plugin.yml) for the exact permission nodes.

## Architecture

```text
TerraForge
├── terraforge-core        coordinates, projections, config and service APIs
├── terraforge-geo         DEM tiles, land cover, spatial indexes and SQLite
├── terraforge-generator   terrain pipeline and Paper ChunkGenerator
├── terraforge-towny       optional Towny/NewTowny bridge
├── terraforge-bluemap     optional BlueMap bridge
├── terraforge-plugin      Paper entry point and the shipped plugin jar
├── terraforge-cli         offline data preparation and validation
└── terraforge-benchmark   JMH performance harness
```

The central architectural rule is simple: **Minecraft never learns about GIS**. Paper types stay in
the generator, plugin and integration modules; core geography remains plain Java. See the
[architecture guide](docs/architecture.md) for module boundaries and data flow.

## Build from source

```bash
./gradlew build
./gradlew :terraforge-plugin:shadowJar
./gradlew :terraforge-cli:shadowJar
```

Artifacts:

- `terraforge-plugin/build/libs/TerraForge-<version>.jar`
- `terraforge-cli/build/libs/terraforge-cli-<version>.jar`

The Gradle wrapper is the supported build entry point. A clean `build` compiles every module and
runs all unit and integration tests.

## Documentation

The [wiki](https://github.com/denfry/TerraForge/wiki) is the player- and operator-facing home for
installation, configuration, commands and troubleshooting. The tables below link the same
material inside this repository for contributors browsing the source.

| Guide | Contents |
|---|---|
| [Installation](docs/installation.md) | Paper setup, data preparation and world creation |
| [Configuration](docs/configuration.md) | Every `terraforge.yml` option |
| [Data sources](docs/data-sources.md) | Recommended datasets and trade-offs |
| [DEM pipeline](docs/dem.md) | Elevation preparation and `.tfdem` |
| [Projections](docs/projection.md) | Scale, origin and distortion |
| [Towny](docs/towny.md) | Town geography integration |
| [BlueMap](docs/bluemap.md) | Marker-set integration |
| [Performance](docs/performance.md) | Budgets, caching and benchmarks |
| [Pregeneration](docs/pregeneration.md) | Preparing chunks ahead of players |
| [Development](docs/development.md) | Architecture rules, testing and style |
| [Releasing](docs/releasing.md) | Versioning and release checklist |
| [Troubleshooting](docs/troubleshooting.md) | Common failures and diagnostics |

## Project status

Version **0.1.0** is the first public preview. The coordinate system, projections, terrain pipeline,
prepared geodata loaders, CLI, commands, caching, pregeneration, Towny integration, BlueMap
integration, tests and benchmarks are implemented. Broader source-format support and production
feedback remain active work.

While the major version is `0`, a minor release may change the world or prepared-data format. Every
such change is called out in the [changelog](CHANGELOG.md).

## Contributing and support

Bug reports, focused pull requests and dataset-validation feedback are welcome. Start with
[`CONTRIBUTING.md`](CONTRIBUTING.md), use the provided issue forms, and read
[`SUPPORT.md`](SUPPORT.md) before opening an installation question.

Please report security issues privately as described in [`SECURITY.md`](SECURITY.md).

## License and data

TerraForge source code is available under the [MIT License](LICENSE).

TerraForge ships **no geodata**. Every dataset is downloaded and prepared by the server operator
under its own licence. Required attribution and redistribution notes are listed in
[`DATA_SOURCES.md`](DATA_SOURCES.md).
