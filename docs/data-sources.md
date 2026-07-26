# Choosing and preparing geodata

Licences and attribution live in [DATA_SOURCES.md](../DATA_SOURCES.md). This document is about
which dataset to pick and how it flows into the world.

## What TerraForge needs

| Provider | Dataset kind | Recommended source | Required? |
|---|---|---|---|
| `ElevationProvider` | DEM raster | Copernicus DEM GLO-30 | **yes** — without it there is no terrain |
| `WaterProvider` | coastline + water polygons | Natural Earth ocean/lakes, HydroLAKES | strongly recommended |
| `LandcoverProvider` | land cover raster | ESA WorldCover | recommended — otherwise biomes fall back to a climate estimate |
| `CountryProvider` | admin boundaries | Natural Earth Admin 0/1 | optional — needed for `/earth whereami` |
| gazetteer | populated places | GeoNames `cities15000` | optional — needed for `/earth city` |

Only the DEM is mandatory. Everything else degrades gracefully: no boundaries means "unknown
country", not a crash.

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

## Land cover to biome

Land cover is the primary biome signal; elevation, latitude and water state refine it. Built-up and
cropland pixels are mapped to the natural vegetation of their surroundings — a city pixel becomes
the forest or grassland that would grow there.

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
