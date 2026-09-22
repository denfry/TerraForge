# Configuration

The file lives at `plugins/TerraForge/terraforge.yml` and is created from the bundled default on
first start. Every option is honoured by the runtime; validation is strict and the plugin refuses to
enable on an invalid configuration rather than generating a broken world.

## `world`

| Key | Default | Meaning |
|---|---|---|
| `name` | `earth` | name of the world TerraForge generates |
| `border.enabled` | `false` | end the world at the edge of the planet |

The projection has no end of its own: longitude wraps, so east of the antimeridian the Earth repeats,
and latitude clamps, so past a pole the polar row repeats forever. With `border.enabled: true` the
world is the one copy of the planet between those seams (at one block per kilometre with the origin
at 0/0: x -20037..20036, z -10018..10017). Chunks beyond it generate as void, players, teleports and
vehicles are held inside, and the vanilla world border -- which can only be a square -- is drawn
around the longer axis. Takes effect on restart. Existing chunks are never rewritten: turning it on
in a live world needs no regeneration, and chunks already generated past the edge simply become
unreachable until their region files are deleted.

## `scale`

| Key | Default | Meaning |
|---|---|---|
| `blocks-per-km` | `1.0` | blocks per kilometre of ground. Supported: 0.5, 1.0, 2.0, 5.0 |

See [projection.md](projection.md) for the resulting world sizes.

## `earth`

| Key | Default | Meaning |
|---|---|---|
| `origin.latitude` | `51.0` | geographic point mapped to Minecraft `0, 0` |
| `origin.longitude` | `10.0` | |
| `projection` | `equirectangular` | `equirectangular`, `web_mercator` or `plate_carree` |

## `terrain`

| Key | Default | Meaning |
|---|---|---|
| `sea-level` | `63` | Y of sea level |
| `min-y` | `-64` | world bottom |
| `max-y` | `320` | world top |
| `vertical-exaggeration` | `1.0` | multiplier on real elevation (0.5–3.0) |
| `meters-per-block` | `1.0` | vertical metres per block |
| `fallback-elevation` | `0.0` | elevation used where no DEM tile exists |
| `bedrock-thickness` | `8` | bedrock layers at the world bottom |
| `smoothing` | `3.0` | width in blocks of the ground a column's height is averaged over (`1.0` = the block's own footprint only) |
| `relief-curve-meters` | `0` | knee of the relief curve in metres; `0` keeps the vertical scale linear |
| `generated-min-y` | `min-y` | lowest Y terrain is generated at; the bedrock starts here, the world below stays empty |
| `generated-max-y` | `max-y` | one above the highest Y terrain may reach |

Out-of-range heights are soft-clamped, never flattened.

`relief-curve-meters` bends the vertical scale. With a knee `R`, `meters-per-block` holds exactly at
sea level and a block at elevation `v` covers `meters-per-block * (1 + v / R)` metres, symmetrically
under the sea. At a kilometre per block horizontally a linear scale cannot win: fine enough to show
a 200 m hill, it multiplies a mountain range's slopes into walls of single-column spikes; coarse
enough for mountains, it flattens every lowland. The curve gives lowlands their hills and mountains
their slopes. `meters-per-block: 15` with `relief-curve-meters: 1300` puts a 100 m hill at six
blocks, the Alps' 3,000 m at about 115 blocks above the sea, and Everest under y=248 of a 256-block
band. Changing it changes column heights: regenerate, do not mix.

`generated-min-y` and `generated-max-y` keep the planet inside a band of an otherwise unchanged
world. The band is what a server with old clients needs: a 1.16 client, joining through
ViaBackwards, sees y 0..255 and nothing else, so a world spanning -512..512 is void below y=0 for
those players -- sea beds, ores and caves included. `generated-min-y: 0`, `generated-max-y: 256`
with the vanilla `min-y: -64`, `max-y: 320` puts every block of terrain where they can see it,
**and needs no height datapack**. That matters beyond this world: the height datapack replaces the
overworld dimension type for every overworld-type world on the server, so a Multiverse spawn or
lobby world loses whatever it had outside the new range. Prefer the band to a changed world height
whenever another overworld-type world shares the server. On startup TerraForge warns when
ViaBackwards is installed and the terrain reaches outside 0..255.

`smoothing` is a box filter over the height field, applied by widening each column's DEM footprint:
at `3.0` every column reports the mean elevation of a 3×3-block window centred on it. Because each
column computes its own window, the filter is seamless across chunk borders and costs no extra
I/O beyond the DEM pages the column already touches. It is deterministic — the same data and
config always give the same heights — and it changes column heights, so changing it on an existing
world leaves a seam between old and new chunks. At one block per kilometre with 20 m per block the
horizontal-to-vertical exaggeration is fifty to one, and without it a 2 % slope is a one-block
step on every block; `3.0` halves that block-to-block texture, `5.0` flattens it further at the
cost of blurring ridges over five kilometres.

## `water`

| Key | Default | Meaning |
|---|---|---|
| `oceans` | `true` | generate oceans and seas |
| `lakes` | `true` | generate inland standing water |
| `rivers` | `true` | generate natural watercourses |
| `default-ocean-depth` | `30.0` | depth in metres where the DEM has no bathymetry |
| `min-visible-river-discharge-cms` | `1.0` | HydroRIVERS discharge (m³/s) below which a prepared river is not carved |
| `min-lake-depth-blocks` | `3` | a lake is never shallower than this many blocks |
| `min-river-depth-blocks` | `2` | the same for rivers |
| `min-ocean-depth-blocks` | `6` | the same for the sea |
| `shore-blend-blocks` | `4` | how far a shore's influence reaches: banks slope in, beds shelve out |

The depth floors exist because real depths are small next to a coarse vertical scale: at 20 m per
block a 6 m lake, a 2 m river and the 30 m default ocean all round to one block of water over a flat
floor. Each water type now has a floor in blocks, and the bed varies smoothly between the floor and
twice it from a seedless noise over the column grid, so a lake is a basin with a deep middle rather
than a slab. Real bathymetry deeper than the floor is untouched. Changing these changes column
heights under water, so regenerate rather than mix.

`shore-blend-blocks` is what makes a lake look like a lake rather than a slab of water in a pit. A
lake sits at its own altitude and the land around it at the DEM's, and at a coarse vertical scale
the two disagree by several blocks. Within this many blocks of the water, a land column is never
higher than the water surface plus its distance from it, so the bank climbs at most one block per
block; and a water column is never deeper than its distance from the land, so the bed shelves out
one block per block before reaching its full depth. Both limits fade out quadratically across the
reach instead of stopping at its edge, so high land behind a beach and a deep bed beyond a shelf
meet the shore without a wall where the blend ends. Land touching water is never lower than that
water's surface -- coastal flats the DEM puts below the sea would otherwise sit beside water held up
by nothing, which floods the moment a player breaks the block next to it. Otherwise the rules only
move the surface towards the water.
Each chunk samples a margin this wide around itself, so a shore just over the chunk border shapes
both sides identically; the cost of sampling grows with the margin (`4` is about twice the columns
of `0`). `0` leaves shores as the data has them.

When `data.database-file` exists and contains prepared entries in `water_bodies`, TerraForge loads
those natural WKB geometries into memory at startup. A missing, empty or unreadable database is
logged and safely falls back to elevation-derived oceans; it never floods unknown data as water.

`min-visible-river-discharge-cms` filters what the running world renders, not what preparation keeps.
HydroRIVERS maps the entire connected network down to the smallest headwaters, and every prepared
line stays in `water_bodies` regardless of this setting -- raising or lowering it later is a config
reload, never a re-`prepare-region`. At coarse `blocks-per-km` the effect matters most: a real 5 m
trickle still floors to a one-block-wide channel (see [data-sources.md](data-sources.md#rivers)), so
rendering every mapped headwater turns into a dense, hairy web rather than distinct rivers. `0.0`
restores the old behaviour of carving every prepared river.

Prepare source water data offline with `terraforge prepare-geo --input <directory> --database
<file>`. Lakes and oceans use GeoJSON `Polygon` or `MultiPolygon` features. River GeoJSON may use
`LineString` or `MultiLineString` with `DIS_AV_CMS`; `prepare-region` also accepts the official
HydroRIVERS `.shp` + `.dbf` pair directly. River lines are widened and their beds carved during
offline preparation. Existing water data is protected; pass `--replace-water` only when deliberately
rebuilding it.

## `biomes`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | map real land cover to Minecraft biomes; `false` uses plains everywhere |
| `edge-noise` | `0.35` | dithering at biome borders so class edges are not pixelated; `0` disables |

## `generation`

| Key | Default | Meaning |
|---|---|---|
| `natural-only` | `true` | no player infrastructure of any kind. Recommended, and the default |
| `caves` | `false` | TerraForge's own karst cave geometry, and nothing else |
| `vanilla-caves` | `false` | *also* hand each chunk to vanilla's cave/canyon carvers and its aquifer |
| `vanilla-decorations` | `false` | hand each chunk to vanilla's whole `applyBiomeDecoration` pass |
| `man-made-structures` | `false` | vanilla's mixed structure pass; keep it off |
| `worker-threads` | `4` | threads for asynchronous chunk data preparation |

`caves` generates deterministic cave geometry only inside prepared WOKAM karst polygons; nearby OSM
`natural=cave_entrance` points can anchor passages. Neither dataset provides global 3D cave surveys.
The switch reaches `KarstCaveCarver` and stops there — with no karst data prepared it generates
nothing at all.

`man-made-structures` keeps villages, temples, mineshafts, strongholds, portals, shipwrecks and
other human-made vanilla structures out of the world.

`caves` was previously reported from `ChunkGenerator.shouldGenerateCaves()`, which is Paper's switch
for *vanilla's* carvers and aquifer rather than TerraForge's carver: the flag documented here as
karst caves in fact enabled vanilla worldgen, while the karst carver ran unconditionally. They are
three keys now because they answer three different questions.

Both vanilla switches default to `false` because Paper's `CustomChunkGenerator` does not adopt this
world's vertical frame. Every vanilla worldgen stage still reads vanilla's `overworld.json` —
`min_y -64`, `sea_level 63`, one metre per block — and knows nothing of `terrain.*`. In a world at
`meters-per-block: 20.0` with `sea-level: 0` that means:

- a routine 30-block vanilla cave removes 600 m of real rock;
- vanilla's `overworld_carver_replaceables` includes `minecraft:water`, so carvers breach the seabed
  instead of running under it;
- the aquifer then refloods whatever was carved below y=63 with water, and below y=-54 with lava —
  1,260 m of real elevation above that world's sea level.

Measured in the audited world: 249,150 fluid blocks inside dry Tibetan rock per region file, and
26.20 % of Gulf-of-Guinea ocean columns containing air. None of it is visible from in-game until
somebody digs into a mountain, which is why the generator logs a WARNING naming this world's sea
level, `min-y` and metres per block against vanilla's whenever either switch is on in a frame that
is not vanilla's. A warning and not a refusal: at `sea-level: 63`, `min-y: -64` and 1.0 m per block
the vanilla stages are exactly as correct here as they are in a vanilla world, and that is a
legitimate configuration. It is the mixture that is broken.

`vanilla-decorations` is all-or-nothing. Paper exposes one boolean for `applyBiomeDecoration`, so
trees, grass and flowers arrive together with ore veins, `spring_water`, `spring_lava`, `lake_lava`,
kelp and seagrass, at vanilla's density — and at coarse `blocks-per-km` a "tree" is kilometres across
while a lava spring lands at altitude. Keeping the vegetation while omitting the lava lakes and the
ore veins needs a biome datapack that edits every feature list, not a flag here, which is why the key
is named for what it does rather than for what one might want from it.

### `generation.vegetation`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | run TerraForge's own vegetation populator |
| `density` | `1.0` | multiplier on every natural placement probability |
| `custom-trees` | `true` | draw TerraForge's own procedural trees alongside vanilla's |
| `farmland` | `true` | till flat cropland into crop fields |
| `farmland-share` | `0.25` | share of flat cropland plots that are tilled; the rest grow as meadow |

This is the vegetation `vanilla-decorations` cannot give a scaled world. It runs as a Paper
`BlockPopulator` on the chunk and its one-chunk buffer, in this world's vertical frame, and places
only plants: trees, short grass, ferns, flowers, dead bushes and cacti. No ore, no lava, no springs.

Real land cover decides *how much* grows — a WorldCover tree-cover pixel is a forest whatever the
climate model says, grassland and cropland are grass with the odd tree, bare and snow-covered
ground grow nothing — and the climate biome decides *what* grows: spruce in the taiga, oak and birch
in temperate forest, jungle trees on the equator, acacia on the savanna, mangroves in mangrove
cover, cacti and dead bushes on desert sand. Where land cover is unknown the biome carries both
roles at a calibrated density. Placement is seeded from the chunk coordinates alone, never from the
world seed, so two servers with the same data and config grow identical forests.

Beyond the calibration, the pass reads each column's surroundings, so the details that make a place
recognisable appear where they belong:

- **River and lake banks** grow sugar cane where water stands level with the bank, willows (or, in
  the desert, palms at an oasis), tall grass, large ferns, blue orchids and firefly bushes.
- **Flower meadows** on grassland come in drifts: one species per twelve-block patch, denser where a
  slow noise says so, with sunflower fields here and there. Temperate forest carries birch groves,
  the odd cherry grove with pink petals beneath it, lilac, peony, rose bushes, leaf litter, bushes and
  mushrooms; rainforest has bamboo groves, moss, dripleaf and torchflowers; the taiga has sweet
  berries, large ferns and fallen logs; the savanna dry grass, baobabs and shrubs; the steppe and
  semi-desert dead trees, dry grass and cypresses; the desert cacti, some in flower.
- **Warm beaches** grow palms. A beach has no climate of its own, so it looks six blocks around for a
  tropical or arid biome before planting one.
- **Cropland** that is level becomes tilled fields when `farmland` is on: each sixteen-block plot is
  one crop -- wheat, potatoes, carrots, beetroot, a pumpkin patch, melons in warm climates -- at one
  ripeness, in rows, with an irrigation channel every eighth row so the farmland stays wet. One plot
  in eight lies fallow as meadow, and sloping cropland stays meadow too. Only `farmland-share` of
  the plots is farmed at all -- at a kilometre per block WorldCover's cropland covers whole regions,
  and tilling all of it made them one endless allotment. Fields ignore `density`.
- **Under water**, lakes and rivers get lily pads on the shallows and seagrass on the bed; temperate
  seas grow kelp forests; warm seas grow coral reefs with sea pickles on the shallows; the frozen
  ocean floor and the abyss stay bare.

`custom-trees` adds eight procedural silhouettes -- willow, palm, baobab, tall pine, cypress, dead
tree, fallen log, shrub -- drawn block by block with persistent leaves, never wider than seven blocks
or taller than twenty-four so they always fit the populator's buffer. A tree that would cross the
buffer is skipped whole rather than cut. Set it to `false` for vanilla `TreeType` features only.

The sea bed is no longer plain gravel: ocean columns draw their surface from a fixed mix -- sand
35 %, gravel 25 %, bone blocks 20 %, clay 15 %, obsidian 5 % -- in five-block patches with ragged
edges, from a seedless hash of the coordinates so every server draws the same floor. Lakes and
rivers keep sand.

South of 60° S the land is the Antarctic ice sheet. Its real elevation, a 2–4 km ice dome that the
vertical scale would turn into a cliff-edged plateau of packed ice, is compressed onto a low dome
(40 m at the coast, 3 % of the real relief inland, so at most about 160 m) and every land column
there is `GLACIER`: snow blocks, with packed ice on a tenth of the surface. This is not
configurable; it changes column heights south of 60° S, so regenerate rather than mix.

At coarse `blocks-per-km` a tree is still a vanilla tree — a few blocks tall on a column that is a
kilometre wide. That is a deliberate choice of readability over scale: a forest a player can see
from the air beats a mathematically honest canopy one twentieth of a block high.

A top-level `vegetation:` block from an older config is still ignored: unknown keys never fail a
load. Its `density` was never
honoured by any code path, and its `enabled` was really the switch for vanilla's entire decoration
pass, now named `generation.vanilla-decorations`.

### `generation.underground`

| Key | Default | Meaning |
|---|---|---|
| `ores` | `true` | coal, iron, copper, gold, redstone, lapis, diamond, and emerald under mountains |
| `ore-multiplier` | `1.0` | multiplier on every ore vein count; `1.0` is vanilla 1.16's density |
| `stone-variety` | `true` | dirt, gravel, granite, diorite, andesite, tuff and blackstone pockets |
| `caves` | `true` | winding cave systems under land |
| `cave-rarity` | `7.0` | one cave system starts in one chunk of this many; lower means more caves |

What `vanilla-decorations` and `vanilla-caves` cannot give a scaled world. The pass runs inside
chunk generation, on Paper's generation threads: it models the chunk's stone from the samples the
generator already holds, places pockets, carves caves and places ore in that order -- vanilla's
order -- and writes each changed block to the chunk once. Nothing is patched into a live world
afterwards, so it costs the server thread nothing and sends no block updates to players.

Every band keeps vanilla 1.16's depth below the sea rather than its absolute Y, so in a world with
`sea-level: 63` and terrain from y=0 the table is exactly vanilla 1.16's -- diamonds and redstone
in y 1..15, gold to 31, iron to 63, coal to 127 -- and in a deeper world it moves down with the sea,
continuing the deep ores at the same density all the way to the bedrock. Mountains above vanilla's
y=127 get coal and some iron. Emerald is placed only where the real ground is at least 1,000 m high.
Ores are 1.16's own plus copper, never deepslate variants, so a 1.16 client through ViaBackwards
sees the same ore the server holds.

Caves are vanilla 1.16's tunnels, rooms and forks, from chunk coordinates alone. They stay six
blocks under the ground, and a further four blocks under any water within four columns -- read from
the sampler's shore margin, so water just over the chunk edge counts too -- so a cave never opens a
hole in a field, drains a lake, runs under a river or breaches the seabed. `generation.caves` is a
different thing: TerraForge's karst carver, constrained to prepared karst regions.

## `pregeneration`

| Key | Default | Meaning |
|---|---|---|
| `max-in-flight` | `1` | maximum chunks requested concurrently. Never unbounded by design |
| `pause-when-players-online` | `true` | auto-pause the job while any player is online |
| `minimum-tps` | `18.0` | auto-pause below this server TPS |
| `maximum-mspt` | `40.0` | auto-pause above this average tick time (ms) |
| `stable-resume-seconds` | `15` | how long health must stay good before an auto-paused job resumes on its own |
| `minimum-free-disk-gb` | `10` | auto-pause (and refuse `/earth pregenerate resume`) below this usable disk space; also the minimum required for `/earth world plan`/`create` |
| `checkpoint-every-chunks` | `128` | how often progress is written to `plugins/TerraForge/pregeneration.json` |

Auto-pause and auto-resume only apply to a job already `RUNNING`; a restart never resumes a job by
itself — see [pregeneration.md](pregeneration.md) and [installation.md](installation.md).

## `infrastructure`

All six keys (`roads`, `buildings`, `railways`, `bridges`, `airports`, `power-lines`) default to
`false` and **must stay false** — setting any to `true` aborts startup with a clear message.
TerraForge has no code that builds man-made features; the section exists so the guarantee is
explicit and auditable.

## `data`

| Key | Default | Meaning |
|---|---|---|
| `data-directory` | `data` | prepared tiles, relative to `plugins/TerraForge/` |
| `cache-directory` | `cache` | on-disk caches |
| `database-file` | `terraforge.db` | SQLite geographic database |

## `cache`

| Key | Default | Meaning |
|---|---|---|
| `memory-limit-mb` | `1024` | soft ceiling across all in-memory caches |
| `dem-tile-cache-entries` | `256` | resident DEM tiles |
| `landcover-grid-cache-entries` | `256` | resident prepared land-cover grids |
| `water-feature-cache-entries` | `4096` | resident decoded natural-water geometries |
| `chunk-cache-entries` | `4096` | resident prepared chunk samples |
| `statistics-interval-seconds` | `300` | how often cache stats are logged in debug mode |

At ~25 MB per `int16` tile, 256 tiles is ~6 GB of mapped files — address space, not heap, but keep
`memory-limit-mb` aligned with the server's actual headroom.

Land-cover grids are heap, not mapped: at the default 600 samples per degree a grid is ~360 KB, so
256 of them is ~92 MB. Both caches are bounded by count *and* by `memory-limit-mb`, whichever binds
first, so neither can be made to exceed the budget by raising the other. This is what makes the size
of a prepared world a disk question rather than a memory one — a whole-Earth import is tens of
thousands of grids on disk and the same 92 MB resident as a single test region.

## `towny` / `bluemap`

| Key | Default | Meaning |
|---|---|---|
| `towny.enabled` | `true` | enable the Towny bridge when Towny is installed |
| `bluemap.enabled` | `true` | enable the BlueMap bridge when BlueMap is installed |
| `bluemap.city-markers` | `true` | show real-world city labels |
| `bluemap.country-labels` | `true` | show country labels |
| `bluemap.max-city-markers` | `300` | upper bound on non-capital city markers; capitals are always kept |
| `bluemap.min-city-population` | `5000` | smallest population a non-capital city needs to get a marker |

Both are soft dependencies: a missing plugin is logged and ignored, never fatal.

## `debug`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` | verbose generator logging |
| `per-player` | `false` | let players with `terraforge.command.debug` toggle their own overlay |

## `test-region`

The bounded area used for development and validation. Defaults to central Europe
(47–55.5°N, 5–15.5°E), covering Germany and its neighbours.

```yaml
test-region:
  name: central-europe
  latitude-min: 47.0
  latitude-max: 55.5
  longitude-min: 5.0
  longitude-max: 15.5
```

## Reloading

`/earth reload` (permission `terraforge.command.reload`) is a **data** reload, not a generator
swap. It:

1. re-reads and re-validates `terraforge.yml` — an invalid file is reported and nothing else runs;
2. clears the caches;
3. reloads the prepared database (countries, regions, cities) and republishes the BlueMap markers.

Step 3 is safe while players are online because none of it is geometry: it is lookup data that
`/earth whereami`, `/earth city`, `/earth teleport` and the web map read.

The generator itself is **deliberately not** hot-swapped. `scale`, `earth.origin`, `projection` and
`terrain.*` keep running with the values the server started with; changing them takes a restart.
This is not an oversight — a live swap would generate new chunks against a different projection
than their neighbours, leaving a permanent seam through the world with no way back.

After re-running `prepare-region`, `/earth reload` is enough to pick up new boundaries and cities.
Towns already annotated keep the country they were resolved against; run `/earth towny refresh` to
re-resolve them.
