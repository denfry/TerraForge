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

Nothing is downloaded at runtime. The data is prepared offline, and the CLI can do the whole job:

```bash
./gradlew :terraforge-cli:shadowJar
CLI=terraforge-cli/build/libs/terraforge-cli-*.jar

# What will this cost?
java -jar $CLI setup --lat-min 47.0 --lat-max 55.5 --lon-min 5.0 --lon-max 15.5 \
    -o ./server/plugins/TerraForge --dry-run

# Do it.
java -jar $CLI setup --lat-min 47.0 --lat-max 55.5 --lon-min 5.0 --lon-max 15.5 \
    -o ./server/plugins/TerraForge
```

`setup` runs four steps against one bounding box, so they cannot disagree about which region is
being built:

1. **Configure** — creates `plugins/TerraForge/` with `data/dem`, `data/landcover`, `cache` and a
   `terraforge.yml` whose `earth.origin` is the centre of the box and whose `test-region` is the box.
   An existing config is kept, not overwritten, unless you pass `--replace-config`.
2. **Fetch** — downloads the sources into `./source-data` (`-i` to change it).
3. **Prepare** — the same work `prepare-region` does.
4. **Validate** — the data-quality checks, for information.

Useful options:

| Option | Effect |
|---|---|
| `--dry-run` | Report the download size and stop |
| `--dem-resolution 30` | Copernicus GLO-30 instead of GLO-90 — about four times the download |
| `--skip landcover` | Leave out a dataset; biomes then fall back to a climate estimate |
| `--cities cities500` | A denser gazetteer than the default `cities15000` |
| `--offline` | Prepare whatever is already in `--source-data`, download nothing |
| `--world`, `--scale`, `--origin-lat`/`--origin-lon` | Override what the config is written with |
| `--replace` | Re-download sources and rebuild prepared data |

A run degrades rather than aborts: an ocean-only DEM tile the publisher does not have, a mirror that
fails, a dataset you skipped — each leaves the rest of the region prepared and is named in the
summary. The source directory is a cache, so re-running fills only the gaps.

### Where the data comes from

| Dataset | Source | Licence |
|---|---|---|
| Elevation | Copernicus DEM GLO-30/GLO-90, AWS Open Data | attribution to ESA/Copernicus |
| Land cover | ESA WorldCover 2021 v200, AWS Open Data | CC BY 4.0 |
| Boundaries, lakes | Natural Earth 1:10m, pinned release | public domain |
| Populated places | GeoNames `cities15000` | CC BY 4.0 |

None of these need an account. `fetch` prints the attribution each one requires when it finishes;
the exact wording is in [DATA_SOURCES.md](../DATA_SOURCES.md). Reservoirs are deliberately not
imported — they are man-made water, and the generator builds only what the Earth made.

### Preparing data you already have

`prepare-region` takes a source tree you assembled yourself, and `fetch` produces exactly that tree,
so the two are interchangeable:

```bash
java -jar $CLI prepare-region \
    --lat-min 47.0 --lat-max 55.5 --lon-min 5.0 --lon-max 15.5 \
    -i ./source-data \
    -o ./server/plugins/TerraForge
```

It expects one directory per dataset under `-i`. A missing directory means "no such data yet": it is
reported and skipped, not treated as an error.

```
source-data/
├── dem/         *.hgt, *.tif   SRTM tiles or GeoTIFF rasters
├── landcover/   *.asc, *.tif   Arc/Info ASCII grids or GeoTIFF (ESA WorldCover codes)
├── boundaries/  *.geojson      countries and first-level regions
├── cities/      *.txt          GeoNames tab-separated export
└── water/       *.geojson      natural water polygons
```

A land-cover GeoTIFF is subsampled into one prepared grid per degree cell; `--samples-per-degree`
(default 600, about 185 m) sets how finely. It must divide the source resolution exactly, so cells
line up with source pixels. Finer than the world can show is wasted server memory: every prepared
grid is held in RAM while the server runs.

Boundary and water files may use either TerraForge's explicit schema (`boundary_type`, `water_type`)
or Natural Earth's published attributes. A file in TerraForge's own schema is read strictly, since it
was written by hand for this purpose; a published dataset is read leniently — territories with no
ISO 3166-1 code are reported and skipped rather than failing the import.

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
