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

A block is not a point. At `blocks-per-km: 1.0` one block spans ~33 DEM samples, and reading the
one sample under its centre is point sampling a signal far above the sampling rate: the difference
between two adjacent samples becomes a block-to-block step in the world, and that step is the
sampler's, not the Earth's. A block's elevation is therefore the **mean of the samples its
geographic footprint covers**, not the value at its centre. Measured against point sampling, the
mean absolute block-to-block height difference fell from 12.75 to 10.71 in the Himalaya and from
10.66 to 8.83 in the Alps — roughly a fifth of the apparent roughness was invented by the sampler.

`CachingChunkSampler` derives that footprint once per chunk, from the chunk's own geographic bounds
divided by sixteen, and passes it down as a latitude/longitude span.
`ElevationProvider.averageElevationAt(lat, lon, latSpan, lonSpan)` then averages every prepared
sample whose coordinate falls inside the footprint:

- **Bounded at 16 samples per axis.** Larger footprints are strided. Without the cap a coarse
  `blocks-per-km` would make one column read a quarter of a tile; sixteen samples per axis is
  already past the point of diminishing returns against arc-second data.
- **Neighbouring tiles are read.** A footprint is a rounding error next to a one-degree cell, so
  this is one tile for almost every column and at most four at a tile corner. Each tile returns a
  sum and a count, which are combined before dividing — a two-sample sliver must not weigh the same
  as a hundred-sample interior.
- **The shared tile edge is counted once.** `.tfdem` tiles are edge-inclusive in the `.hgt` style,
  so a tile owns its north and west edges and leaves its east and south ones to the neighbours that
  repeat them. Averaging both copies would weight one line of ground twice.
- **A footprint finer than the DEM grid falls back to interpolating the centre.** At fine
  `blocks-per-km` a block sits between samples and covers none of them; that is the normal case,
  not a hole in the data.

Averaging is deterministic — the same footprint always reads the same samples, so identical data
and configuration still produce identical terrain. It also costs nothing extra in I/O: the samples
are on the pages the point sample would have touched anyway.

Ad-hoc point queries still point-sample. `/earth whereami` and the CLI ask about a place rather
than about a block, so `sampleColumn(lat, lon)` with no spans interpolates the exact coordinate.

### Interpolating one point

`DemTile.interpolate(lat, lon)` does bilinear interpolation between the four surrounding samples.
No-data neighbours are dropped from the weighting rather than dragging the result toward the
sentinel, so a coastal sample beside a void keeps its own height. A height is reported only when
**at least half the bilinear weight came from prepared samples**; below that the point has no
elevation at all and the caller applies its fallback.

The former rule renormalised over whichever corners happened to hold data, which answers a
different question. A point sitting almost entirely over a void was handed the height of the one
distant corner that had a sample, and returned it as an elevation indistinguishable from a measured
one — an invented height, in the exact place where honest "no data" matters most. Half the weight
is the line: the point is nearer real ground than void, or it has none. Coastal behaviour is
unchanged, because a point mostly over real data still keeps its own height.

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
