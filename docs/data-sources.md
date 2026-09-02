# Choosing and preparing geodata

Licences and attribution live in [DATA_SOURCES.md](../DATA_SOURCES.md). This document is about
which dataset to pick and how it flows into the world.

## What TerraForge needs

| Provider | Dataset kind | Recommended source | Required? |
|---|---|---|---|
| `ElevationProvider` | DEM raster | Copernicus DEM GLO-30 | **yes** — without it there is no terrain |
| `WaterProvider` | coastline, water polygons + river lines | Natural Earth ocean/lakes + HydroRIVERS, plus HydroLAKES for lake altitudes | strongly recommended |
| `LandcoverProvider` | land cover raster | ESA WorldCover | recommended — otherwise biomes fall back to a climate estimate |
| `CountryProvider` | admin boundaries | Natural Earth Admin 0/1 | optional — needed for `/earth whereami` |
| gazetteer | populated places | GeoNames `cities15000` | optional — needed for `/earth city` |
| `KarstProvider` | karst aquifer polygons | WHYMAP WOKAM v1 | optional — needed only for `generation.caves`, TerraForge's own carver |

Only the DEM is mandatory. Everything else degrades gracefully: no boundaries means "unknown
country", not a crash.

`terraforge setup` fetches and prepares all of the recommended sources for a bounding box in one
command — see the [installation guide](installation.md). This document is for deciding when to
depart from those defaults.

## Choosing a resolution

Match the source resolution to the world scale — finer data than one block can show is wasted disk.

| `blocks-per-km` | Ground per block | Useful DEM resolution |
|---|---|---|
| 0.5 | 2 km | 90 m (GLO-90) |
| 1.0 | 1 km | 90 m or 30 m |
| 2.0 | 500 m | 30 m |
| 5.0 | 200 m | 30 m |

## The natural-features-only rule

The importer works from a **whitelist**. Man-made geometry is dropped at preparation time, so it
cannot reach the database, the generator or the world:

- accepted: coastlines, ocean, lakes, natural watercourses, administrative boundaries, place names,
  land cover
- rejected: `highway`, `building`, `railway`, `bridge`, `aeroway`, `power`, industrial and
  residential land use, and anything else man-made

This is enforced in code, not by convention, and it is the reason OSM can be used at all: it
contributes rivers and coastlines, never roads.

## Rivers

HydroRIVERS line features are buffered during preparation.  Their channel width is
`max(1000 / blocks-per-km, 7.2 × sqrt(DIS_AV_CMS))` metres, with discharge in m³/s. The first
term deliberately widens sub-block rivers to one block rather than dropping them: at the default
1 block/km, a physically accurate 10–100 m river cannot survive a 1,000 m grid cell, and silently
removing the connected river network would be less truthful than showing its navigable course.

The bed is then carved by `clamp(width / 20, 1, 10)` metres below the DEM-derived water surface.
This keeps water below both banks without inventing a replacement valley. Run `prepare-region` with
the same `--blocks-per-km` as the world (and re-prepare after changing scale); `setup` passes it
automatically. Published source data is recognised leniently, but canal and reservoir labels are
rejected at preparation time under TerraForge's natural-only rule.

Every prepared river is kept, discharge estimate and all, in `water_bodies.discharge_cms` --
preparation never drops part of the connected network. What actually gets carved into a running
world is a separate, later decision: `water.min-visible-river-discharge-cms` (see
[configuration.md](configuration.md#water)) hides rivers below that discharge from generation without
touching the database, because HydroRIVERS' smallest headwaters, all carved to at least one block
wide, otherwise read as a dense web rather than a river network.

`fetch` downloads the publisher's global Shapefile ZIP anonymously, extracts its `.shp` and `.dbf`
members, and `prepare-region` reads those two files offline. No server process downloads or parses
source vectors at runtime.

## Lakes

A lake is placed only if the prepared data states **where its surface is**. `water_bodies` carries
two columns for that:

| Column | Rivers | Lakes | Ocean |
|---|---|---|---|
| `surface_elevation_m` | NULL — a centreline has no absolute level; the surface is the terrain the river runs through, resolved at generation time | HydroLAKES `Elevation` | `0` |
| `bed_depth_m` | width-derived channel depth | HydroLAKES `Depth_avg` | `0` — the floor is the DEM's bathymetry, or `water.default-ocean-depth` |

NULL means **unknown**, and the runtime then declines to place that water body at all rather than
assume sea level: at 30°N 84°E sea level is five kilometres below the ground, and a lake told to sit
there takes the mountain with it. Lake Geneva sits at 372 m with a 154.4 m bed carved below that
surface, so the surrounding valley — and the lake's real altitude — survive.

The practical consequence is a source question. Natural Earth's lake polygons carry no elevation
attribute, so those lakes import with a NULL surface and generate as dry land under the biome the
climate implies. Import HydroLAKES (or any source shipping `Elevation`/`surface_elevation_m` and
`Depth_avg`/`bed_depth_m`, case variants included) for lakes that actually hold water.

A declared surface is also cross-checked against the DEM at the same column and rejected when the
two disagree by more than the bed depth plus 250 m. A DEM measures a lake at its water surface, so
on real data the two agree to a few tens of metres; a kilometre-scale disagreement means the
attribute or the polygon is wrong, and honouring it would carve or flood the column by that whole
difference.

These two columns were added after 0.1.2. A `terraforge.db` prepared before them is **rejected at
startup** with the command that fixes it — re-run `terraforge prepare-geo` (or `prepare-region`)
against the database — and the plugin falls back to the elevation-derived water provider for that
session. Refusing is deliberate: without `surface_elevation_m` every lake's altitude is unknown, and
the world would generate with every lake silently missing. Prepared `.tfdem` DEM tiles are not
affected and need no re-preparation.

## Caves

Caves are generated geometry, constrained by real karst polygons and anchored at real OSM cave
entrances when one is nearby. They are not surveyed cave geometry, because no open global dataset of
that exists — individual cave surveys are per-cave, in Survex and Therion formats, with no worldwide
coverage. Describing the result as "real caves" would be a straightforward lie; describing it as
"caves only where caves can actually form" is accurate.

`fetch` downloads the WOKAM Shapefile ZIP from BGR anonymously and extracts every `.shp` in it into
`karst/`, because the archive's layer names are not a published contract and have changed between
editions. `prepare-region` reads polygon records and ignores layers holding anything else, so a
springs or points layer beside the karst layer costs an import nothing.

Two things then stand between prepared data and a cave:

- `generation.caves` defaults to `false`. It changes what an existing world generates, so turning it
  on is deliberate, not a side effect of downloading a dataset. It drives TerraForge's carver alone
  — vanilla's carvers and aquifer live behind `generation.vanilla-caves` and read none of this data.
- With no karst data prepared, the setting generates nothing at all rather than falling back to
  noise. Caves confined to real karst is the whole point; caves everywhere would be vanilla.

Every decision the carver makes derives from the geographic coordinate, never from the world seed,
so the same configuration and the same data produce the same caves on every server and every run.

Cave entrances are optional. Without them the systems are placed from the karst polygons alone.

## Land cover to biome

Land cover is the primary biome signal; elevation, latitude and water state refine it. Built-up and
cropland pixels are mapped to the natural vegetation of their surroundings — a city pixel becomes
the forest or grassland that would grow there.

Prepare a WGS84 raster offline, then put the compact output in
`plugins/TerraForge/data/landcover/`. The CLI reads ESA WorldCover class codes from GeoTIFF or from
Arc/Info ASCII Grid (`.asc`) and writes `.tflc`. GeoTIFF needs no GDAL step:

```bash
java -jar terraforge-cli.jar prepare-landcover \
  -i ESA_WorldCover_10m_2021_v200_N45E006_Map.tif \
  -o plugins/TerraForge/data/landcover
```

A GeoTIFF covers several degree cells at a resolution far finer than any world can show, so it is
sliced into one grid per cell and subsampled to `--samples-per-degree` (default 600, about 185 m).
The step must divide the source resolution exactly, so 600 works for WorldCover's 12000 pixels per
degree and 700 does not — the command says which divisors are available. Resolution here costs
server memory directly: every prepared grid stays resident while the server runs, so at the default
1 block per kilometre there is nothing to gain above the default.

An ASCII grid is one region and becomes one file, so `-o` is the `.tflc` path rather than a
directory. Existing prepared grids are preserved by default; use `--overwrite` only when
intentionally replacing a grid.

## Place names

`prepare-cities` reads the standard GeoNames tab-separated export. Besides the primary name it
stores the ASCII form and up to twelve of GeoNames' alternate names, so `/earth city Munich`,
`/earth city Muenchen` and `/earth city München` all find the same place, and tab completion
matches any of them. Where an alternate name is ambiguous, the largest place wins — which is what
somebody typing it almost always means.

The cap is deliberate: GeoNames carries every localisation of a large city, and a planet-wide import
of all of them would be millions of rows nobody ever searches.

## Checking a prepared database

Importing validates one feature at a time. Some problems are only visible between features:

```bash
java -jar terraforge-cli.jar validate -d server/plugins/TerraForge/terraforge.db
```

It reports counts and geographic coverage, then checks for overlapping country polygons, duplicate
ISO codes and country names, regions outside their country, countries with no capital or with
several, and cities assigned to a country they do not lie in.

Errors exit with code 65; warnings alone exit 0 unless `--strict` is given, which makes the command
usable as a CI gate. Nothing is ever repaired — the fix belongs in the source dataset, where it
stays fixed.

The importer preserves source pixel order, maps only documented WorldCover codes, and converts
unknown or no-data pixels to `UNKNOWN`. Prepared grids are immutable and loaded once at startup;
the server never parses raster files while generating chunks.

| Real-world class | `ClimateBiome` | Minecraft |
|---|---|---|
| desert / bare | `DESERT` | Desert |
| tundra | `TUNDRA` | Snowy Plains |
| needleleaf forest | `BOREAL_FOREST` | Taiga |
| broadleaf forest | `TEMPERATE_FOREST` | Forest |
| tropical forest | `TROPICAL_RAINFOREST` | Jungle |
| savanna | `SAVANNA` | Savanna |
| grassland / cropland | `GRASSLAND` | Plains |
| wetland | `WETLAND` | Swamp |
| permanent snow | `GLACIER` | Ice Spikes / Snowy Slopes |
| water | ocean / lake / river variants | Ocean, Lake, River |

The mapping table lives in `terraforge-generator`; the vocabulary itself
(`dev.terraforge.core.terrain.ClimateBiome`) is platform-neutral.

## Storage estimates

| Region | DEM (30 m, int16) | Database |
|---|---|---|
| central-europe test region | ~2.2 GB | ~50 MB |
| Europe | ~20 GB | ~200 MB |
| whole Earth | ~1.5 TB | ~2 GB |

Use GLO-90 to cut DEM size roughly nine-fold when the scale does not justify 30 m.
