# Installation

## Requirements

- Paper 1.21.8 (see `gradle.properties` for the exact target)
- Java 21
- Prepared geodata (see [dem.md](dem.md) and [data-sources.md](data-sources.md))
- Optional: Towny, BlueMap

## 1. Install the plugin

```bash
./gradlew :terraforge-plugin:shadowJar
cp terraforge-plugin/build/libs/TerraForge-*.jar server/plugins/
```

Start the server once. TerraForge writes `plugins/TerraForge/terraforge.yml` and logs its banner:

```
====================================
TerraForge - Real Earth Engine
====================================
World:        earth
Scale:        1.0 blocks/km (1000 m per block)
Projection:   Equirectangular -- exact north-south distance, standard parallel 51.00 degrees
Origin:       51.000000, 10.000000
Vertical:     sea-level 63, exaggeration 1.0, 1.0 m/block
Test region:  central-europe [47.0000,5.0000 -> 55.5000,15.5000]
Towny:        ENABLED
BlueMap:      NOT INSTALLED (integration skipped)
Natural-only: ENABLED
====================================
```

## 2. Prepare the data

Nothing is downloaded at runtime. Prepare the test region offline:

```bash
./gradlew :terraforge-cli:shadowJar

java -jar terraforge-cli/build/libs/terraforge-cli-*.jar prepare-region \
    --lat-min 47.0 --lat-max 55.5 --lon-min 5.0 --lon-max 15.5 \
    -i ./source-data \
    -o ./server/plugins/TerraForge
```

`prepare-region` expects one directory per dataset under `-i`. A missing directory means "no such
data yet": it is reported and skipped, not treated as an error.

```
source-data/
├── dem/         *.hgt      SRTM tiles
├── landcover/   *.asc      Arc/Info ASCII grids
├── boundaries/  *.geojson  countries and first-level regions
├── cities/      *.txt      GeoNames tab-separated export
└── water/       *.geojson  natural water polygons
```

Everything is clipped to the bounding box, so a planet-wide gazetteer produces a regional database.
Boundaries are imported before cities, because a city resolves its country by ISO code as it is
inserted; the whole vector import runs in one transaction, so a broken source file leaves the
database exactly as it was. Re-running the command refuses to overwrite prepared data unless you
pass `--replace`.

Resulting layout:

```
plugins/TerraForge/
├── terraforge.yml
├── terraforge.db    countries, regions, cities, water bodies
├── data/
│   ├── dem/         N47E005.tfdem, ...
│   └── landcover/   *.tflc
└── cache/
```

Verify before starting:

```bash
java -jar terraforge-cli.jar info -c server/plugins/TerraForge/terraforge.yml
```

## 3. Create the world

Add to `bukkit.yml`:

```yaml
worlds:
  earth:
    generator: TerraForge
```

Or, with Multiverse:

```
/mv create earth normal -g TerraForge
```

The world name must match `world.name` in `terraforge.yml`.

## 4. Verify

```
/earth info        server-wide configuration
/earth whereami    your country, region, nearest city, lat/lon, elevation
/earth teleport city Frankfurt
/earth teleport country DE
```

Country and city names complete with <kbd>Tab</kbd>, as do the subcommands of `teleport`,
`pregenerate`, `towny`, `cache` and `debug`. Names containing a space are not offered — Bukkit
splits arguments on spaces — but typing them out still works.

While debugging terrain, `/earth debug` prints a one-off report for the block you are standing on
and `/earth debug overlay` follows you in the action bar. The overlay needs both `debug.enabled` and
`debug.per-player` in `terraforge.yml`; the repeating task exists only while somebody has it on.

## 5. Optional integrations

Both are detected at runtime. Install them, restart, and the banner reports `ENABLED`. Neither is
required — a missing plugin is logged and skipped. See [towny.md](towny.md) and
[bluemap.md](bluemap.md).

## Upgrading

1. Stop the server.
2. Replace the jar.
3. Start. New config keys are added with their defaults; unknown keys are ignored, so older files
   keep working.

Do **not** change `scale`, `earth.origin`, `projection` or `terrain.*` on a world that already has
generated chunks — new chunks would not line up with old ones.
