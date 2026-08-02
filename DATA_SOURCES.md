# Data sources, licences and attribution

TerraForge **ships no geodata**. This repository contains code only; `.gitignore` excludes every
raster and vector format so datasets cannot be committed by accident. The server operator downloads
the data and is responsible for complying with its licence, including attribution.

## Letting the CLI download them

`terraforge fetch` (and `terraforge setup`, which calls it) downloads the recommended defaults for a
bounding box. It changes nothing about the licensing position: the files come from the publisher, to
your machine, under the publisher's terms, and the command prints the attribution each one requires
when it finishes. Nothing is redistributed by this project, and the running server never downloads
anything.

| Dataset | Mirror | Needs an account? |
|---|---|---|
| Copernicus DEM GLO-30 / GLO-90 | `copernicus-dem-30m` / `copernicus-dem-90m` on AWS Open Data | no |
| GEBCO 2024 bathymetry | CEDA / BODC GEBCO archive | no |
| ESA WorldCover 2021 v200 | `esa-worldcover` on AWS Open Data | no |
| Natural Earth 1:10m admin 0/1, lakes | `nvkelso/natural-earth-vector`, pinned to a release tag | no |
| HydroRIVERS v1.0 | `data.hydrosheds.org` | no |
| GeoNames `cities500`…`cities15000` | `download.geonames.org` | no |
| WHYMAP WOKAM v1 karst aquifers | `download.bgr.de` | no |

Natural Earth is pinned to a tag rather than a branch so the same command prepares the same world
later; the rasters are already versioned in their own paths. Ocean-only DEM tiles are simply not
published, and a 404 for one is reported as "not published", not as a failure.

Anything else — a different DEM, a national dataset, a higher-detail boundary set — is downloaded by
hand into the same layout and prepared with `prepare-region`.

---

## Elevation (DEM)

| Dataset | Resolution | Coverage | Licence | Notes |
|---|---|---|---|---|
| **Copernicus DEM GLO-30** | 30 m | global, 85°N–85°S | free, attribution required to ESA/Copernicus | recommended default |
| Copernicus DEM GLO-90 | 90 m | global | same | smaller download, fine at 1 block/km |
| NASA SRTM 1 Arc-Second (SRTMGL1 v3) | 30 m | 60°N–56°S | public domain, attribution to NASA/USGS | no high latitudes |
| ASTER GDEM v3 | 30 m | 83°N–83°S | free with attribution to METI/NASA | noisier than Copernicus |
| GEBCO 2024 | ~450 m | global, **bathymetry** | free with attribution to GEBCO | use for ocean depth |

Land DEMs stop at the shoreline. `prepare-region` merges GEBCO automatically when both source
directories are present, and keeps ocean-only cells at GEBCO's native 15 arc-second resolution.

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
| **HydroRIVERS v1** | global natural river-line network (`DIS_AV_CMS`) | CC BY 4.0 |
| OpenStreetMap `natural=water`, `waterway=river/stream` | detailed natural water | ODbL 1.0 |

HydroRIVERS is downloaded anonymously from HydroSHEDS. TerraForge uses its discharge estimate only
to derive a deterministic visible channel width; it rejects labelled canals and reservoirs while
preparing the database. Attribution: *Lehner, B. and G. Grill (2013), Global river hydrography and
network routing: baseline data and new approaches to study the world's large river systems.*

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

## Caves and karst

| Dataset | Contents | Licence |
|---|---|---|
| **WHYMAP WOKAM v1** | global karst aquifer polygons — soluble rock | no access restrictions, attribution required |
| OpenStreetMap `natural=cave_entrance` | real cave entrance points | ODbL 1.0 |

**These datasets do not contain caves.** No open global dataset of surveyed cave geometry exists;
what does exist is where caves *can* form, and where a few of them open at the surface. WOKAM maps
the first, OSM the second. Everything TerraForge puts underground inside those polygons is
**generated geometry constrained by real data**, not a survey — do not describe it as real caves.

WOKAM is downloaded and unpacked by `fetch` into `karst/`. Every `.shp` in the archive is extracted,
because which layer holds the polygons is BGR's business and has changed between editions; the
importer reads polygon records and quietly ignores layers of anything else. Cave entrances are
optional: the generator anchors a system at a real entrance when one is nearby and works from the
karst polygons alone when none is.

Caves are **off by default** (`generation.caves: false`). Enabling them changes existing worlds, so
it is an explicit decision, and it needs prepared karst data — with none, the setting generates
nothing.

Attribution, required by the BGR terms:

> Datenquelle: WHYMAP WOKAM, © BGR Berlin, IAH Reading, KIT Karlsruhe, UNESCO Paris 2017

DOI [10.25928/b2.21_sfkq-r406](https://doi.org/10.25928/b2.21_sfkq-r406). Published 25 September
2017; BGR's terms are at [bgr.bund.de/AGB_en](https://www.bgr.bund.de/AGB_en).

### If the WOKAM download fails at the TLS handshake

`download.bgr.de` presents a chain rooted in **HARICA TLS RSA Root CA 2021**. Some JDK truststores
carry only HARICA's 2015 roots — Oracle JDK 21 is one — so Java rejects a certificate every browser
on the same machine accepts. `fetch` reports this as a refused handshake rather than an unreachable
host, because they call for opposite fixes. Any of these resolves it:

```bash
# Windows: trust the certificates Windows already trusts
java -Djavax.net.ssl.trustStoreType=Windows-ROOT -jar terraforge-cli.jar fetch ...

# any platform: add the root to the JDK truststore, once
keytool -importcert -alias harica-tls-rsa-2021 -cacerts -file HARICA-TLS-RSA-Root-CA-2021.crt
```

Or skip the download entirely — `--skip karst` — fetch the ZIP in a browser and unpack its `.shp`
files into `source-data/karst/`. `prepare-region` cannot tell the difference between a fetched tree
and a hand-assembled one.

---

## Preparation summary

```
source rasters  →  terraforge prepare-dem   →  plugins/TerraForge/data/dem/*.tfdem
source vectors  →  terraforge prepare-geo   →  plugins/TerraForge/terraforge.db
```

Provenance of every import (dataset name, version, licence, import date) is recorded in the
`cache_metadata` table so a server can always state where its world came from.
