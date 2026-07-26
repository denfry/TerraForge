# DEM preparation

The server never reads GeoTIFF. Source rasters are transcoded **offline** by the CLI into
`.tfdem` tiles, which are memory-mapped and sampled directly.

## 1. Download source data

See [DATA_SOURCES.md](../DATA_SOURCES.md). For the central-europe test region you need the tiles
covering 47–56°N, 5–16°E: 90 one-degree tiles (Copernicus GLO-30 or SRTMGL1).

```
source-data/dem/
├── Copernicus_DSM_COG_10_N47_00_E005_00_DEM.tif
├── ...
```

## 2. Transcode

```bash
java -jar terraforge-cli.jar prepare-dem \
    -i ./source-data/dem \
    -o ./server/plugins/TerraForge/data/dem
```

The input directory is searched recursively for `.hgt`, `.tif` and `.tiff`. Output: one file per
one-degree cell, named after its south-west corner.

```
plugins/TerraForge/data/dem/
├── N47E005.tfdem
├── N47E006.tfdem
└── ...
```

| Option | Default | Effect |
|---|---|---|
| `--encoding` | `auto` | `int16`, `float32`, or match the source |
| `--samples-per-tile` | finest source | force the grid, e.g. `1201` to coarsen GLO-30 |
| `--overwrite` | off | rewrite tiles that already exist |

Encodings:

| Encoding | Bytes/sample | Use when |
|---|---|---|
| `int16` | 2 | metre precision, ±32,767 m range |
| `float32` | 4 | sub-metre precision, or merged bathymetry |

`auto` picks `float32` when the source stores floating-point samples and `int16` otherwise, which is
what you want unless you are deliberately trading precision for disk.

A 3601×3601 `int16` tile is ~25 MB; the 90 tiles of the test region are ~2.2 GB on disk. Only the
tiles actually being generated are resident in memory (`cache.dem-tile-cache-entries`).

### Supported sources

| Format | Notes |
|---|---|
| SRTM `.hgt` | square, big-endian int16, named `N50E008.hgt`; `-32768` is a void |
| GeoTIFF | **WGS84 geographic only**, north-up, single band |

A projected GeoTIFF is refused rather than resampled as if its metres were degrees:

```
utm.tif is a projected GeoTIFF (ModelType 1). TerraForge reads WGS84 geographic rasters only --
convert it with: gdalwarp -t_srs EPSG:4326 utm.tif out.tif
```

Both pixel-is-point (Copernicus, SRTM) and pixel-is-area conventions are honoured; getting that
half-pixel wrong would shift a whole country, so it is read from the GeoKeyDirectory rather than
assumed.

Sources need not line up with degree cells. One source may feed several tiles and several sources may
feed one tile; where sources overlap, the first with real data wins and later ones fill its voids.
Every output sample is resampled, so preparation absorbs the misalignment once instead of the server
paying for it per chunk.

## The `.tfdem` format

Fixed 64-byte header, then a raw row-major sample grid from the north-west corner.

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `TFDM` |
| 4 | 2 | format version |
| 6 | 2 | sample encoding (1 = int16, 2 = float32) |
| 8 | 4 | grid width |
| 12 | 4 | grid height |
| 16 | 4 | south edge latitude, integer degrees |
| 20 | 4 | west edge longitude, integer degrees |
| 24 | 4 | no-data sentinel |
| 28 | 4 | vertical scale numerator |
| 32 | 4 | vertical scale denominator |
| 36 | 28 | reserved |
| 64 | … | samples |

Big endian throughout. The format is deliberately trivial: loading a tile is an `mmap`, not a decode.

The grid is **pixel-is-point** and includes both edges: a tile of `n` samples per side has a spacing
of `1/(n-1)` degrees, and its north-west sample sits exactly on the cell corner. Neighbouring tiles
therefore share their edge samples, which is what lets a point on a tile boundary resolve from either
side.

The no-data sentinel is `-32768` for `int16` tiles and `NaN` for `float32` ones. Tiles are written to
a temporary file and moved into place atomically, so an interrupted preparation run leaves no tile
rather than a half-written one the server would happily sample as terrain.

## Sampling

`DemTile.interpolate(lat, lon)` does bilinear interpolation between the four surrounding samples.
No-data neighbours are excluded from the weighting rather than dragging the result toward the
sentinel; if all four are missing, the result is no-data and the caller applies its fallback.

At `blocks-per-km: 1.0` one block spans ~33 DEM samples, so interpolation is effectively
downsampling — expect smoothed terrain. Higher `blocks-per-km` values expose the DEM's real detail.

## Missing tiles

A missing tile is never fatal:

```
[TerraForge-Geo] Missing DEM tile: N52E013 (no prepared file); falling back to
terrain.fallback-elevation (0.0 m)
```

The fallback order is: prepared tile → cached neighbour → `terrain.fallback-elevation`. Each missing
tile is logged once, not once per chunk.

Check coverage before generating:

```bash
java -jar terraforge-cli.jar info \
    -c plugins/TerraForge/terraforge.yml \
    --dem plugins/TerraForge/data/dem
```

```
DEM tiles:    90 prepared in plugins/TerraForge/data/dem
DEM coverage: [47.0000,5.0000 -> 56.0000,16.0000]
Region gaps:  none
```

The tile directory is scanned once at startup, so tiles added while the server is running are not
seen until a restart. That is deliberate: generation threads must not stat the filesystem to answer
"is there data here".

## Bathymetry

Land DEMs stop at the coast, so the ocean floor is flat by default at
`water.default-ocean-depth` metres below sea level. For real ocean depth, merge GEBCO into the DEM
during preparation and use `float32` encoding; `ElevationProvider.hasBathymetry()` then reports
`true` and the generator uses real depths.
