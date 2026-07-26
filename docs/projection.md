# Projection, scale and distortion

## The chain

```
lat/lon  --[projection]-->  plane metres  --[scale + origin]-->  Minecraft X/Z
```

Two separate concerns, two separate classes:

- `Projection` turns degrees into metres on a plane. It knows nothing about blocks.
- `CoordinateTransformer` turns plane metres into blocks, relative to the configured origin.

Adding a projection is a `ProjectionRegistry.register(...)` call — the generator does not change.

## Available projections

### `equirectangular` (default)

Standard parallel = `earth.origin.latitude`.

- north-south distance: **exact everywhere**
- east-west distance: exact on the standard parallel, off by `cos(parallel)/cos(lat)` elsewhere
- covers the poles
- best choice for a regional world; at ±5° from the standard parallel the east-west error is ≈1%

### `web_mercator`

- conformal: local shapes (coastlines, mountain ranges) are correct
- distances stretched by `1/cos(lat)` — ×1.56 at 50°N, ×2.9 at 70°N
- undefined beyond ±85.05°: the poles cannot be generated
- matches the tiling of most raster datasets

### `plate_carree`

Equirectangular with the standard parallel at the equator. Simple, heavily distorted in Europe.
Useful for whole-planet worlds where a familiar world-map shape matters more than accuracy.

Distortion is available at runtime as `Projection.scaleFactor(latitude)`; one block covers
`metersPerBlock / scaleFactor(lat)` real ground metres, exposed as
`CoordinateTransformer.groundMetersPerBlock(lat)`.

## Scale

`scale.blocks-per-km` — how many blocks one kilometre of ground occupies.

| Value | One block | Central Europe test region | Whole Earth (equator) |
|---|---|---|---|
| 0.5 | 2 km | ~370 × 470 blocks | ~20,000 × 10,000 blocks |
| **1.0** | 1 km | ~740 × 950 blocks | ~40,000 × 20,000 blocks |
| 2.0 | 500 m | ~1,470 × 1,890 blocks | ~80,000 × 40,000 blocks |
| 5.0 | 200 m | ~3,680 × 4,730 blocks | ~200,000 × 100,000 blocks |

Changing the scale changes the whole world layout — treat it as a decision made once, before the
world is generated.

Run `terraforge info` to see the exact block extent for your configuration.

## Origin

`earth.origin` is the geographic point that maps to Minecraft `0, 0`. The default is 51°N 10°E,
the centre of the central-europe test region, so spawn lands in the middle of the prepared data.

Sign convention: **+X is east, +Z is south** (Minecraft Z grows south, projected northing grows
north — `CoordinateTransformer` negates it).

## Vertical scale

Horizontal and vertical scaling are independent:

```
y = sea-level + elevation_m × vertical-exaggeration / meters-per-block
```

- `vertical-exaggeration` — 0.5 / 1.0 / 1.5 / 2.0 / 3.0. Makes terrain more dramatic.
- `meters-per-block` — vertical metres per block. Raise it to fit tall mountains into the world.

Values outside the build limits are **soft-clamped**: they are compressed into the last few blocks
rather than flattened, so Everest still ends up above Mont Blanc. The result is always strictly
inside `min-y … max-y`.

At `1.0 / 1.0`, Everest (8,849 m) is far above `max-y: 320` and lands in the compressed band. For a
world where high mountains keep their relative shape, use `meters-per-block: 30` — 8,849 m then
becomes Y≈358 before clamping, so raise `max-y` accordingly, or accept the compression.

## Known limitations

- Antimeridian-crossing bounding boxes are not supported; split them.
- Web Mercator cannot generate the polar caps.
- Scale and projection are baked into a generated world: changing them requires regenerating it.
