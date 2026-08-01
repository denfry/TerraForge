<p align="center">
  <img src="https://raw.githubusercontent.com/denfry/TerraForge/main/docs/assets/terraforge-banner.png" alt="A real-Earth landscape transformed into natural voxel terrain" width="800">
</p>

<p align="center">
  <img alt="Paper" src="https://img.shields.io/badge/Paper-1.21.8-222?logo=papermc">
  <img alt="Java" src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk">
  <img alt="License" src="https://img.shields.io/github/license/denfry/TerraForge">
  <img alt="Release" src="https://img.shields.io/github/v/release/denfry/TerraForge?include_prereleases&sort=semver">
</p>

<h3 align="center">Real Earth terrain generation for Paper</h3>
<p align="center"><em>Natural geography in, player-built history out.</em></p>

```
REAL EARTH DATA → TerraForge CLI → natural Minecraft terrain → players build → Towny governs → BlueMap displays
```

> [!IMPORTANT]
> **0.1.x preview software.** Test on a new world and keep backups. Changes to projection, scale
> or terrain settings between versions can create permanent seams between old and new chunks — read
> the changelog before upgrading.

---

### What is TerraForge?

Generated caves are optional and default off. When enabled, their geometry is generated only
inside prepared WOKAM karst polygons and may be anchored at real OSM `natural=cave_entrance`
points; this is not a global 3D cave survey. Man-made vanilla structures remain off by default
through `generation.man-made-structures`.

TerraForge turns prepared real-world elevation, coastlines, water and land-cover data into
**deterministic** Minecraft terrain. It generates the planet beneath the players — everything
man-made is deliberately out of scope, forever.

| ✅ Generated from real data | ❌ Never generated |
|---|---|
| Continents, coastlines and islands | Roads, highways and streets |
| Ocean depth, lakes and natural rivers | Buildings, villages and structures |
| Mountains, valleys, plains and deserts | Railways, bridges and airports |
| Biomes from real land cover | Power lines or industrial areas |

`generation.natural-only: true` is the default and cannot be turned off — the generator contains
no code path that places man-made structures. TerraForge fails closed if an infrastructure switch
is ever enabled.

### Highlights

- 🏔️ **Real relief** — mountains, valleys and coastlines come from prepared DEM data.
- 🌿 **Natural biomes** — land cover, latitude, elevation and water state drive biome selection.
- 📦 **No runtime downloads** — production servers only read local, prepared data.
- 🎯 **Deterministic** — the same configuration and source data always produce the same world.
- 🛡️ **Conservative fallbacks** — missing coverage degrades visibly and safely, never invents data.
- 🔌 **Optional integrations** — Towny/NewTowny and BlueMap are detected automatically at runtime.
- 🛠️ **Operator tooling** — a separate offline CLI prepares, validates and inspects regional datasets.

---

### Requirements

| Component | Version |
|---|---|
| Server | Paper 1.21.8 |
| Runtime | Java 21 |
| TerraForge data | Prepared locally with the matching `terraforge-cli` release |
| Optional | Towny / NewTowny, BlueMap |

TerraForge ships **no geodata**. Every dataset is downloaded by the operator from its publisher,
under that publisher's licence — see [DATA_SOURCES.md](https://github.com/denfry/TerraForge/blob/main/DATA_SOURCES.md).
The running server never downloads anything.

### Quick start

1. Download `TerraForge-<version>.jar` from this page and drop it in `plugins/`.
2. Start the server once so `terraforge.yml` is generated, then stop it.
3. Build a bounded test region with the CLI (downloaded from the
   [GitHub release](https://github.com/denfry/TerraForge/releases)). It downloads the elevation,
   land-cover, boundary and gazetteer data for the box, prepares it, writes a matching config and
   validates the result:

```bash
java -jar terraforge-cli-<version>.jar setup \
    --lat-min 47.0 --lat-max 55.5 --lon-min 5.0 --lon-max 15.5 \
    -o ./server/plugins/TerraForge
```

Add `--dry-run` first to see how large the download is.

4. Register the generator in `bukkit.yml`:

```yaml
worlds:
  earth:
    generator: TerraForge
```

5. Start the server and verify:

```
/earth info
/earth whereami
```

Full installation guide, data-source recommendations and every `terraforge.yml` option are
documented in the [installation guide](https://github.com/denfry/TerraForge/blob/main/docs/installation.md)
and [configuration reference](https://github.com/denfry/TerraForge/blob/main/docs/configuration.md).

<details>
<summary><strong>📜 Commands</strong></summary>

Root command `/earth` — aliases `/tf`, `/terraforge`.

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

Exact permission nodes are listed in
[`plugin.yml`](https://github.com/denfry/TerraForge/blob/main/terraforge-plugin/src/main/resources/plugin.yml).

</details>

<details>
<summary><strong>🔌 Integrations</strong></summary>

- **Towny / NewTowny** — towns are annotated with real coordinates, elevation, country and region
  on creation, spawn move and rename. `/earth towny refresh` backfills towns created before the
  plugin was installed.
- **BlueMap** — cities, capitals, countries and regions are published as TerraForge-owned marker
  sets, re-published after every BlueMap reload. Only `terraforge-`-prefixed sets are ever touched.

Both are detected automatically — no extra setup beyond having them installed.

</details>

<details>
<summary><strong>📊 Metrics</strong></summary>

TerraForge reports anonymous usage statistics via [bStats](https://bstats.org/plugin/bukkit/TerraForge/33013)
(active servers, projection choice, integration usage). Disable it any time in
`plugins/bStats/config.yml`.

</details>

---

### Project status

Version **0.1.x** is the first public preview. The coordinate system, projections, terrain
pipeline, prepared geodata loaders, CLI, commands, caching, pregeneration, Towny integration,
BlueMap integration, tests and benchmarks are implemented. Broader source-format support and
production feedback remain active work.

While the major version is `0`, a minor release may change the world or prepared-data format.
Every such change is called out explicitly in the
[changelog](https://github.com/denfry/TerraForge/blob/main/CHANGELOG.md).

### Links

- 📖 [Documentation](https://github.com/denfry/TerraForge/tree/main/docs)
- 🐛 [Issue tracker](https://github.com/denfry/TerraForge/issues)
- 💬 [Support](https://github.com/denfry/TerraForge/blob/main/SUPPORT.md)
- 📄 [Changelog](https://github.com/denfry/TerraForge/blob/main/CHANGELOG.md)
- ⚖️ [MIT License](https://github.com/denfry/TerraForge/blob/main/LICENSE)
