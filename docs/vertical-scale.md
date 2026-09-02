# Vertical scale and world height

The horizontal scale is a preference. The vertical scale is a budget, and it is the one setting that
fails invisibly: a world configured too shallow generates without a single warning and looks correct
until you stand on a mountain range that turned out to be a plateau.

## The arithmetic

Minecraft's dimension type allows a world floor no lower than −2032 and a ceiling no higher than
2032 — **4,064 blocks**, absolute maximum. Earth's relief spans 19,784 m, from the Mariana Trench at
−10,935 m to Everest at +8,849 m.

So **one metre per block is impossible at any setting**. Everest alone is more than twice the tallest
world Minecraft can have. The question is never whether to compress, only how much.

Nor is compression a defect. At true proportions — 1,000 m per block, matching `blocks-per-km: 1.0` —
Everest is nine blocks tall, because Earth's relief really is negligible against its width. Every
playable choice exaggerates the vertical; picking `meters-per-block` is picking by how much.

| `meters-per-block` | Everest | Mariana | World height needed | Sections/chunk | Vertical exaggeration |
|---|---|---|---|---|---|
| 1000 | 9 blocks | 11 blocks | vanilla | 24 | 1× — true proportions |
| 100 | 88 | 109 | vanilla | 24 | 10× |
| 50 | 177 | 219 | ~400 | 32 | 20× |
| **20** | **442** | **547** | **1024** | **64** | **50×** |
| 10 | 885 | 1094 | 2048 | 128 | 100× — near-vertical walls |
| 1 | 8849 | 10935 | impossible | — | 1000× |

"Exaggeration" is relative to the horizontal scale: at `blocks-per-km: 1.0` one block is 1,000 m
sideways, so 20 m of vertical block makes every slope fifty times steeper than reality.

## The two profiles

`init` and `setup` choose one from the bounding box, and either can be overridden field by field
with `--meters-per-block`, `--min-y`, `--max-y` and `--sea-level`.

**Regional** — anything smaller than a planet. `sea-level: 63`, `−64..320`, 1 m per block. Vanilla
world height, no datapack, a quarter of the memory. Honest for a region whose highest ground is a few
hundred metres; wrong the moment the box contains an alpine range.

**Planet** — `--whole-world`. `sea-level: 0`, `−512..512`, 20 m per block.

```yaml
terrain:
  sea-level: 0
  min-y: -512
  max-y: 512
  meters-per-block: 20.0
  vertical-exaggeration: 1.0
```

Everest lands 442 blocks above sea level, the abyssal plain 185 below, the continental shelf and the
mid-ocean ridges are all distinguishable. The cost is 64 chunk sections against vanilla's 24 — 2.7×
the per-chunk memory and tick cost.

The Mariana Trench needs 547 blocks and gets 512, so its floor is compressed into the last few. It
remains the deepest place in the world, which is the property that matters; buying it another 512
blocks of world height for every chunk on the planet is not a trade worth making. If you want it
uncompressed, `--min-y -576 --max-y 448` is the same 1,024 blocks shifted down.

Leave `vertical-exaggeration` at `1.0`. It multiplies elevation before the conversion to blocks, so
it works directly against fitting Earth into the world.

## The datapack

World height belongs to the dimension type, which Minecraft reads from a datapack before any plugin
loads. A generator cannot raise it from inside, and a world created without the pack silently keeps
vanilla's 384 blocks — at which point `min-y` and `max-y` in `terraforge.yml` are aspirations that
the chunk data clamps away.

`init` and `setup` write a reference copy of the pack under `plugins/TerraForge/datapack/` whenever
the profile is not vanilla height, so it exists on disk before any world does. For the managed
`earth` world, though, you never copy it by hand: `/earth world create` renders this same pack from
the live `terraforge.yml` and stages it directly into `world/earth/datapacks/terraforge-earth-height/`
as part of the staging transaction described in [installation.md](installation.md), and the restart
that follows is what makes Paper pick it up. It overrides `minecraft:overworld` rather than adding a
dimension, because the world TerraForge generates *is* the overworld; every other value in it is
vanilla's, so nothing but the height changes.

Manual copying is a fallback only for building or inspecting a world outside the managed workflow
(for example, a throwaway local test world); the primary `earth` world must go through
`/earth world create` because Paper 1.21.8+ only lets the primary world carry a non-vanilla
dimension type.

## Vanilla worldgen does not share this frame

The datapack settles what the *world* is. It does not settle what vanilla's own generation stages
believe. Paper wraps a plugin generator in `CustomChunkGenerator`, and that wrapper reports vanilla's
`getMinY()` and `getSeaLevel()` — vanilla's `overworld.json`: `min_y -64`, `sea_level 63`, one metre
per block — not this world's. Any vanilla stage left enabled therefore works in a vertical frame
unrelated to `terrain.*`, with no error and no clamp.

Two switches expose it, and both default to `false`: `generation.vanilla-caves` (vanilla's
cave/canyon carvers and its aquifer) and `generation.vanilla-decorations` (vanilla's whole
decoration pass). At `meters-per-block: 20.0` a 30-block vanilla cave removes 600 m of real rock,
vanilla's carvers may replace water so they breach the seabed rather than run under it, and the
aquifer then refloods the breach with water up to y=63 — 1,260 m of real elevation above that
world's sea level — and with lava below y=-54.

`TerraForgeChunkGenerator` logs a WARNING at startup when either switch is on and this world's frame
is not vanilla's, naming both frames:

```
[WARNING] generation.vanilla-caves/vanilla-decorations are enabled, but this world's
          vertical frame (sea level 0, min y -512, 20.0 m per block) is not vanilla's
          (63/-64/1.0).
```

It warns rather than refuses because the regional profile — `sea-level: 63`, `−64..320`, 1 m per
block — *is* vanilla's frame, and there those stages are as correct as they are in a vanilla world.
Only the mixture is broken. See [configuration.md](configuration.md#generation).

## Checking it

The startup banner reports what the configuration can actually represent:

```
Vertical:     sea-level 0, exaggeration 1.0, 20.0 m/block
Relief:       true shape from -10,080 m to 10,080 m -- all of Earth fits
```

and says so plainly when it cannot:

```
Relief:       true shape from -119 m to 249 m -- land above 249 m is compressed
[WARNING] This world can show less than 249 m of relief, so mountain ranges will
          generate as one plateau.
```

Fix it before generating. Changing `meters-per-block`, `sea-level`, `min-y` or `max-y` on a world
that already has chunks leaves a permanent seam where the old terrain meets the new.
