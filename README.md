# Cubic Chunks Extended (NeoForge 1.21.1)

A Cubic Chunks–style world-height extension for Minecraft **1.21.1 / NeoForge (21.1.x)**.
Build height and depth become configurable (default **`-2048 .. 1024`**, absolutely safe up to
roughly **±8 000 000** with care) and everything **below the vanilla band is streamed as true
16×16×16 cubic chunks**, generated on demand around each player's vertical position.

![mod id](https://img.shields.io/badge/modid-cubicchunks-blue)

## Concept

Vanilla 1.21.1 stores and streams worlds as vertical **columns** of 16×16 sections spanning the
whole world height. This mod keeps exactly that machinery (compatibility!) and *adds* a cubic
layer on top:

| Region | What happens | Compatibility |
|---|---|---|
| **Vanilla band** (e.g. `y = -64..320` for the overworld) | 100 % vanilla worldgen pipeline: density noise, surface rules, carvers, features, **structures**, **modded biomes/ores/blocks** — bit-for-bit identical to a datapack-tall world | Everything that works with a taller datapack world works |
| **Below the band** (your configured depth) | **Extension cubes** streamed in around each player: the *actual vanilla noise machinery* evaluated at the cube's real coordinates — density caves (cheese caverns, entrances, spaghetti, pillars) keep working below the band because the band-anchored terrain gradients are re-baselined at depth, aquifers keep their shapes (deep lava pockets become water/dry caves), biomes are sampled with a climate remap onto vanilla cave biomes, and vanilla/modded features (ores, veins, springs, geodes) place through the real placement pipeline: each feature's native vertical span is measured once, then **tiled downward** over the whole deep range, with placement counts increasing per stratum (`oreDepthBonusPerStratum`) | Everything below is vanilla math extended downward — no custom noise; structures are the vanilla ones translated per stratum (see below) |
| **Above the band** (your configured height) | Empty buildable sky (optionally extended future work) | Building, flying and mods behave normally |

Columns are still the storage unit (vanilla region files), and extension cubes simply occupy the
sections of their column — so **persistence and chunk lifecycle are vanilla**. What "cubic" buys
you: only the cubes near players are generated (descending to `y=-2000` doesn't materialise your
savefile into 2048-block-deep columns you never entered), and the world remains fast because tall
empty sections cost nothing.

### Concurrently enabled dimensions

Any dimension id can be enabled, including modded ones. Non-noise generators (flat, custom) fall
back to a strata filler (stone/netherrack/deepslate + caves).

## Configuration (`config/cubicchunks-common.toml`)

```toml
[general]
# Dimensions receiving cubic world height.
enabledDimensions = ["minecraft:overworld"]

[world]
# World bounds; snapped outwards to multiples of 16. Height must stay >= 512.
minY = -2048
maxY = 1024

[streaming]
# How many 16-block cubes vertically around each player's eye are streamed.
verticalLoadDistance = 5
# Extension cube generation budget per server tick (4-tick scan cadence).
generationBudgetPerTick = 2

[generation]
# Bedrock exists only at the world bottom (worldMinY); when false, no bedrock exists anywhere
# (the vanilla band floor at y=-64 becomes deepslate too, so you can dig straight down).
deepBedrockFloor = true
# Map biomes below the band onto vanilla cave biomes (dripstone/lush/deep dark) instead of
# letting climate extrapolation produce nonsense biomes at extreme depth.
deepBiomeRemap = true
# No lava below y=-54; lava exists only within this many blocks above the world floor
# (worldMinY). Set 0 for no deep lava at all.
lavaNearFloorDepth = 32
# Ores keep their vanilla Y ranges (no depth-scaling): each feature's native range is tiled
# downward through the deep world, so an ore that generates at y=-64 keeps generating below the
# band at the band's own per-block density, and each band-height stratum below multiplies that
# density by (1 + stratum * this). Set 0 for constant density at exactly the band's.
oreDepthBonusPerStratum = 0.5
# Translate vanilla structures (ancient cities, villages, galleons, ...) into every
# stratum below the band: each structure's pieces are NBT-copied and shifted down by
# whole band periods, then placed through vanilla's own piece pipeline (beard/terrain
# adaptation included).
deepStructures = true

[debug]
# <0 disables. If >=0, pre-generates that many extension sections below spawn at startup.
startupPregenSections = -1
# Pregen ring radius (columns) around spawn, for startupPregenSections.
startupPregenRadius = 0
# On player join: teleport them to y=-96 in a cubic dimension and log how many cubes
# streamed in (end-to-end vertical streaming test).
autoDepthTest = false
```

The mod works **server-side**; clients need the mod too because world height participates in chunk
decoding (bounds are synced from the server at login; a stale client config is never authoritative).

## Commands

- `/cubicchunks status` — current dimension's effective range, band window, generated cube count.
- `/cubicchunks bounds` — effective `[minY, maxY)`.
- `/cubicchunks tp <y>` — teleport yourself vertically (great for exploring the deep world).
- `/cubicchunks regen [radius]` — rebuilds the terrain of the generated extension cubes (every
  loaded column, or only those within `radius` columns of you) **without removing player changes**:
  each cube is regenerated and merged over the installed one, so only blocks vanilla generation
  could have produced are replaced; anything you placed stays, and structure interiors are re-placed
  rather than filled solid.
- `/cubicchunks verify <y>` — diff one generated cube at `<y>` against the full-column
  reference (vanilla noise evaluated over the whole modded range).
- `/cubicchunks verifyall <radius>` — diff *every* generated cube in that many columns around
  you, one column per tick; logs per-column mismatch histograms plus a bulk total. With
  `startupPregenSections` set, run it right after boot: a clean world reports only feature-
  placement diffs (a handful of air residue where a deep geode carved its cavity), never whole
  cubes of air.

## Architecture (modder notes)

- `mixin/ServerLevelMixin` / `mixin/ClientLevelMixin` add *real* `getMinBuildHeight()` /
  `getHeight()` implementations, shadowing the `LevelReader` interface defaults. Chunks
  (`ChunkAccess`) already delegate to the level accessor, so sections, heightmaps, light engine,
  serializers and pathing regions inherit the new range for free.
- `core/ExtensionCubeManager` — per-dimension server tick: builds the desired cube set around each
  player within their chunk tracking view (nearest columns first), generates missing cubes within a
  per-tick budget, lights them, places the cube's deep features, and re-sends chunks to tracking
  clients (capped per tick).
  Two things make the per-cube work cheap and were the difference between unplayable and fine:
  structure pieces are **indexed by section Y once per column** (built from the starts of already-
  loaded columns, never from `StructureManager#startsForStructure`, which loads neighbours and
  deadlocked worldgen at spawn), and each feature's native span is **measured once and cached**
  instead of re-rolled per cube. In the test configuration that took pre-generating 900 deep
  cubes from 24.8 s to 3.4 s (~3.8 ms/cube). The dominant leftover cost is the per-cube noise
  setup itself, which is the price of true cubic streaming.
- `gen/ExtensionGenerator` — builds a tiny per-cube `NoiseChunk` (`NoiseSettings(cubeY, 16, …)`)
  over the world's own `RandomState`, replaying `NoiseBasedChunkGenerator#doFill` over a 16-block
  window; applies deep strata (deepslate gradient, optional bedrock floor at the world bottom) and
  samples deep biomes.
- `gen/CubeGenAccess` — a `WorldGenLevel` adapter for feature placement; writes clip to generated
  cubes, exactly like vanilla clips feature writes to the current region.

Global worldgen mixins (inspired by and credited to the C2Chunks reference implementation):

- `YClampedGradientMixin` — re-baselines the band-anchored terrain gradients below y=-64 so
  vanilla's own cave branch (cheese caverns, entrances, spaghetti, pillars) stays active at depth.
- `CountPlacementMixin` / `RepeatingPlacementMixin` — while the extension planner evaluates,
  placement counts are scaled for the current tiling; the vanilla band itself is never scaled
  (a thread-local gate), so band decoration stays bit-for-bit vanilla. There is deliberately
  **no vertical anchor scaling** (`AbsoluteVerticalAnchorMixin` was dropped): ores keep their
  vanilla Y ranges.
- Feature planning model (`core/ExtensionCubeManager.placeCubeFeatures`): heights are sampled from the
  *vanilla-exact* distribution against the band bounds — there is no anchor scaling at all, ores keep
  their vanilla Y ranges. The feature's native vertical span is measured once and cached per feature,
  and its range is then **tiled downward by that span**, so the space under the band is covered
  continuously rather than only where vanilla's ranges happen to dip. Because one vanilla pass
  scatters its blobs across the whole band height, each tile's count is scaled by span/band-height to
  hold per-block density at the band's, then multiplied by `1 + stratum * oreDepthBonusPerStratum`
  (stratum = band heights below the band): an ore that generates at y=-64 keeps generating below the
  band at the band's own density, growing with depth. Writes are clipped to the cube window.
- `core/DeepStructures` — translates vanilla structures into the deep world. Each piece is
  round-tripped through its own NBT (`StructurePiece#createTag`), the `BB`/`TP`/`POS` Y
  components are shifted by whole band periods, and **all strata are merged into one combined
  `StructureStart` per column**, so references, Beardifier terrain adaptation and vanilla
  `placeStructurePieces` keep working at depth. It reads only the chunk's own structure marks
  (`ChunkAccess#getAllStarts`); asking the `StructureManager` for starts would load neighbour
  chunks from inside STRUCTURE_STARTS and deadlock the worldgen mailbox during spawn generation.
- Generated-cube persistence: the set of generated sections is saved as a `long[]` bitset in the
  chunk NBT (`ChunkDataEvent` save/load), so reloading a world neither re-generates nor loses
  deep content. Sections that vanilla carvers wrote residue into (empty deep sections filled by
  carving) are re-generated on load instead — that residue alone is what used to leave voids
  you fall through.
- `gen/ExtensionGenerator#verifyAllCubesAgainstFullColumn` — the correctness oracle: one
  full-column `NoiseChunk` evaluated over `[minY, maxY)`, diffed against every streamed cube.
  Zero terrain mismatches (only feature-placement diffs) is the proof that per-cube noise equals
  full-column noise.
- `NoiseBasedAquiferMixin` + `WorldCarverMixin` — no lava below y=-54 (vanilla floods everything
  below that line with lava); lava exists only near the bottom of the modded range
  (`lavaNearFloorDepth` above `worldMinY`), so deep caves are dry/water-pockmarked and the world
  floor keeps a lava belt.
- `StateRuleMixin` — relocates the bedrock floor to the configured world bottom; the vanilla band
  floor at y=-64 becomes deepslate (configurable).
- `ClimateSamplerMixin` — maps deep biome sampling onto vanilla cave biomes.

### Deliberate limits

- Structures DO generate in every stratum (`deepStructures`), but as *translated copies* of the
  band's structures: a piece's biome decision stays the band's (deep-biome remap is not re-validated
  per stratum), jigsaw junction anchors keep band-Y beard terrain, and procedurally generated
  pieces (nether-fortress style, `runtime`) may randomise differently per cube since they are
  re-evaluated rather than copied.
- Surface (heightmap) features don't repeat in strata — they anchor to the surface, which is also
  what vanilla would do with infinite depth.
- Vanilla carvers (the older wormhole cave carvers) only run during column generation, i.e. in the
  vanilla band; deep caves come from vanilla's density-driven caves instead.
- Feature positions are rolled per cube; ore blobs that cross a cube boundary are clipped (like
  vanilla clips features at not-yet-generated chunk borders).
- Y is limited to `SectionPos` packing (±8 388 592), not full int range; use saner configs even if
  you get adventurous, every additional cube of world height costs base memory per loaded column.

## Building

```bash
./gradlew build
```

(Requires a JDK 21 toolchain; Gradle will provision one automatically if absent.)
