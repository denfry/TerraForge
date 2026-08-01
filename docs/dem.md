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

`terraforge fetch` also downloads the one anonymous GEBCO 2024 GeoTIFF covering the requested
box into `source-data/bathymetry/`. The running server never downloads GEBCO or any other source.
Use `prepare-region` with that source tree to merge it with Copernicus. For a hand-supplied,
ocean-only GEBCO raster, use `prepare-dem --bathymetry` to write native-resolution ocean tiles.

## 2. Transcode

```bash
java -jar terraforge-cli.jar prepare-dem \
    -i ./source-data/dem \
    -o ./server/plugins/TerraForge/data/dem \
    --encoding int16
```

Two input formats are read directly:

| Format | Notes |
|---|---|
| SRTM `.hgt` | one file per degree cell; the cell comes from the file name, e.g. `N50E008.hgt` |
| GeoTIFF `.tif`, `.tiff` | north-up WGS84 only; sliced into the degree cells it covers |

A GeoTIFF is read through its `ModelPixelScale` and `ModelTiepoint` tags, so any extent works — one
file covering a whole country becomes as many tiles as it touches. Cloud-optimised GeoTIFFs, the
form Copernicus DEM is published in, are read in strips of rows rather than one row at a time: a COG
stores square tiles, so a single-row read still decodes every tile that row crosses. Rotated,
projected or BigTIFF rasters are **rejected with the GDAL command that fixes them** rather than
silently misplaced:

```bash
gdalwarp -t_srs EPSG:4326 input.tif wgs84.tif
```

Output grid resolution follows the source: one-arc-second data gives 3601 samples per axis and
three-arc-second data 1201, the same shape as `.hgt`. Resampling is nearest-neighbour on purpose —
a DEM is measured data, and interpolating during preparation would bake in elevations no survey
recorded. The raster's `GDAL_NODATA` value becomes a void, not a zero.

Output: one file per one-degree cell, named after its south-west corner.

```
plugins/TerraForge/data/dem/
├── N47E005.tfdem
├── N47E006.tfdem
└── ...
```

Encodings:

| Encoding | Bytes/sample | Use when |
|---|---|---|
| `int16` | 2 | default; metre precision, ±32,767 m range |
| `float32` | 4 | sub-metre precision |

A 3601×3601 `int16` tile is ~25 MB; the 90 tiles of the test region are ~2.2 GB on disk. Only the
tiles actually being generated are resident in memory (`cache.dem-tile-cache-entries`).

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
| 36 | 1 | v2 flags; bit 0 means the tile contains GEBCO bathymetry |
| 37 | 27 | reserved, zero-filled |
| 64 | … | samples |

Big endian throughout. The format is deliberately trivial: loading a tile is an `mmap`, not a decode.

## Sampling

`DemTile.interpolate(lat, lon)` does bilinear interpolation between the four surrounding samples.
No-data neighbours are excluded from the weighting rather than dragging the result toward the
sentinel; if all four are missing, the result is no-data and the caller applies its fallback.

At `blocks-per-km: 1.0` one block spans ~33 DEM samples, so interpolation is effectively
downsampling — expect smoothed terrain. Higher `blocks-per-km` values expose the DEM's real detail.

## Missing tiles

A missing tile is never fatal:

```
[TerraForge-Generator] Missing DEM tile: N52E013 (no prepared file)
[TerraForge-Generator] Falling back to terrain.fallback-elevation (0.0 m)
```

The fallback order is: prepared tile → cached neighbour → `terrain.fallback-elevation`. Each missing
tile is logged once, not once per chunk.

Check coverage before generating:

```bash
java -jar terraforge-cli.jar info -c plugins/TerraForge/terraforge.yml
```

## Bathymetry

GEBCO 2024 is 15 arc-second (about 450 m) int16 elevation data. `prepare-region` writes ocean-only
degree cells at GEBCO's native approximately 241-by-241 grid, so it does not inflate the ocean DEM
onto the 1201/3601 land grid. Where a Copernicus and GEBCO cell overlap, positive Copernicus samples
win; GEBCO fills voids and values at or below sea level with nearest-neighbour sampling only. The
resulting tile is marked in the v2 header, so `ElevationProvider.hasBathymetry()` is true and the
terrain pipeline uses real depths rather than `water.default-ocean-depth`.

Version 1 tiles remain readable. Their former float32-is-bathymetry interpretation is retained only
for compatibility; new tiles always use the explicit header flag.
