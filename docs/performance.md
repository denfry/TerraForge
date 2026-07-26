# Performance

## Budgets

Chunk generation must stay well inside a server tick's budget when run on the generation threads.
Targets on a modern desktop CPU, `blocks-per-km: 1.0`, warm cache:

| Metric | Target |
|---|---|
| average chunk generation | < 5 ms |
| p95 | < 15 ms |
| p99 | < 30 ms |
| DEM cache hit rate (steady state) | > 95 % |
| main-thread GIS work | 0 |

## Rules the design enforces

Forbidden, by construction:

- HTTP request per chunk — all data is prepared offline
- reading a whole GeoTIFF at runtime — the server only reads `.tfdem`
- SQL query per block — polygons are loaded once into an in-memory R-tree
- scanning every country polygon per point — bounding-box pre-filter first
- synchronous heavy GIS on the main thread
- unbounded caches — every cache is registered with `CacheManager` and bounded

Sampling happens **per column**: 256 samples per chunk, not 256 × 384.

## Caches

| Cache | Bound | Contents |
|---|---|---|
| `dem` | `cache.dem-tile-cache-entries` (256) | memory-mapped DEM tiles |
| `chunk` | `cache.chunk-cache-entries` (4096) | prepared `ChunkSamples` |
| `geo` | memory limit | country/region lookups by cell |

```
/earth cache stats
```

reports entries, hit and miss rate, evictions, and estimated memory per cache, plus total pressure
against `cache.memory-limit-mb`.

## Benchmarks

```bash
./gradlew :terraforge-benchmark:jmh
```

`CoordinateBenchmark` measures the coordinate maths that runs for every column — the floor for
generation cost:

- block → geographic, Web Mercator and equirectangular
- a full chunk's 256 column conversions
- elevation → block Y
- Haversine vs Vincenty distance

DEM sampling, biome resolution and terrain shaping get their own benchmarks as those stages land.

## Profiling method

Measure before optimising:

1. `/earth cache stats` — a low hit rate means the tile cache is too small for the play area.
2. Paper's built-in `/timings` or spark, filtered to the generator threads.
3. `./gradlew :terraforge-benchmark:jmh` for the isolated pipeline stages.

Common findings and their fixes:

| Symptom | Likely cause | Fix |
|---|---|---|
| high p99, low average | tile cache thrashing | raise `dem-tile-cache-entries` |
| uniformly slow generation | DEM resolution far finer than the scale | prepare GLO-90 instead of GLO-30 |
| main thread stalls | synchronous provider call | move it to the worker pool |
| memory pressure > 1.0 | caches over budget | lower cache entries or raise `memory-limit-mb` |

## Pregeneration

The cheapest chunk is one generated before a player asks for it. See
[pregeneration.md](pregeneration.md).
