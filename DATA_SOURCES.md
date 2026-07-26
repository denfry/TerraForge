# Data sources, licences and attribution

TerraForge **ships no geodata**. This repository contains code only; `.gitignore` excludes every
raster and vector format so datasets cannot be committed by accident. The server operator downloads
the data and is responsible for complying with its licence, including attribution.

---

## Elevation (DEM)

| Dataset | Resolution | Coverage | Licence | Notes |
|---|---|---|---|---|
| **Copernicus DEM GLO-30** | 30 m | global, 85°N–85°S | free, attribution required to ESA/Copernicus | recommended default |
| Copernicus DEM GLO-90 | 90 m | global | same | smaller download, fine at 1 block/km |
| NASA SRTM 1 Arc-Second (SRTMGL1 v3) | 30 m | 60°N–56°S | public domain, attribution to NASA/USGS | no high latitudes |
| ASTER GDEM v3 | 30 m | 83°N–83°S | free with attribution to METI/NASA | noisier than Copernicus |
| GEBCO 2024 | ~450 m | global, **bathymetry** | free with attribution to GEBCO | use for ocean depth |

Land DEMs stop at the shoreline. For realistic ocean floors, merge a land DEM with GEBCO during
preparation, or accept the configured `water.default-ocean-depth` fallback.

Required attribution examples:

- Copernicus: *"Produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and
  Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights
  reserved."*
- GEBCO: *"GEBCO Compilation Group (2024) GEBCO 2024 Grid."*

---

## Country and region boundaries

| Dataset | Detail | Licence |
|---|---|---|
| **Natural Earth Admin 0/1** | 1:10m, 1:50m, 1:110m | public domain |
| geoBoundaries | high | CC BY 4.0 |
| GADM | very high | free for **non-commercial** use only — check before using on a public server |

Natural Earth 1:10m is the recommended default: public domain, small, and detailed enough for
country and first-level region detection.

---

## Populated places (gazetteer)

| Dataset | Licence |
|---|---|
| **GeoNames cities500 / cities15000** | CC BY 4.0 |
| Natural Earth Populated Places | public domain |

Cities are stored as a name plus a coordinate. They are used for teleporting, "nearest city" and map
labels. **Nothing is built in the world.**

---

## Land cover (biomes)

| Dataset | Resolution | Licence |
|---|---|---|
| **ESA WorldCover 2021 v200** | 10 m | CC BY 4.0 |
| Copernicus Global Land Cover | 100 m | free with attribution |
| MODIS Land Cover (MCD12Q1) | 500 m | public domain (NASA) |

Built-up and cropland classes are deliberately mapped to the natural vegetation of the surrounding
climate — TerraForge renders no settlements or fields.

---

## Water

| Dataset | Contents | Licence |
|---|---|---|
| **Natural Earth lakes / ocean** | coastlines, large lakes | public domain |
| HydroLAKES | detailed lake polygons | CC BY 4.0 |
| HydroRIVERS | natural river network | CC BY 4.0 |
| OpenStreetMap `natural=water`, `waterway=river/stream` | detailed natural water | ODbL 1.0 |

### OpenStreetMap usage policy

If OSM data is used at all, it may supply **only** natural features:

- allowed: `natural=water`, `natural=coastline`, `waterway=river|stream|riverbank`, administrative
  boundaries, place names
- forbidden: `highway=*`, `building=*`, `railway=*`, `bridge=*`, `aeroway=*`, `power=*`,
  `landuse=industrial|residential|commercial`, and every other man-made feature

The importer enforces this by whitelist: unlisted tags are dropped at preparation time, so man-made
geometry never reaches the database, let alone the world. ODbL is share-alike — read it before
redistributing derived data.

---

## Preparation summary

```
source rasters  →  terraforge prepare-dem   →  plugins/TerraForge/data/dem/*.tfdem
source vectors  →  terraforge prepare-geo   →  plugins/TerraForge/terraforge.db
```

Provenance of every import (dataset name, version, licence, import date) is recorded in the
`cache_metadata` table so a server can always state where its world came from.
