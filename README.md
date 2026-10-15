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
| **Below the band** (your configured depth) | **Extension cubes** streamed in around each player: the *actual vanilla noise machinery* evaluated at the cube's real coordinates — density caves (cheese caverns, entrances, spaghetti, pillars) keep working below the band because the band-anchored terrain gradients are re-baselined at depth, aquifers keep their shapes (deep lava pockets become water/dry caves), biomes are sampled with a climate remap onto vanilla cave biomes, and vanilla/modded features (ores, veins, springs) place through the real placement pipeline with depth-scaled anchors | Everything below is vanilla math extended downward — no custom noise; structures and heightmap-anchored surface features stay in the vanilla band |
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

[debug]
# <0 disables. If >=0, pre-generates that many extension sections below spawn at startup.
startupPregenSections = -1
```

The mod works **server-side**; clients need the mod too because world height participates in chunk
decoding (bounds are synced from the server at login; a stale client config is never authoritative).

## Commands

- `/cubicchunks status` — current dimension's effective range, band window, generated cube count.
- `/cubicchunks bounds` — effective `[minY, maxY)`.
- `/cubicchunks tp <y>` — teleport yourself vertically (great for exploring the deep world).

## Architecture (modder notes)

- `mixin/ServerLevelMixin` / `mixin/ClientLevelMixin` add *real* `getMinBuildHeight()` /
  `getHeight()` implementations, shadowing the `LevelReader` interface defaults. Chunks
  (`ChunkAccess`) already delegate to the level accessor, so sections, heightmaps, light engine,
  serializers and pathing regions inherit the new range for free.
- `core/ExtensionCubeManager` — per-dimension server tick: builds the desired cube set around each
  player within their chunk tracking view (nearest columns first), generates missing cubes within a
  per-tick budget, lights them, places the cube's deep features, and re-sends chunks to tracking
  clients (capped per tick).
- `gen/ExtensionGenerator` — builds a tiny per-cube `NoiseChunk` (`NoiseSettings(cubeY, 16, …)`)
  over the world's own `RandomState`, replaying `NoiseBasedChunkGenerator#doFill` over a 16-block
  window; applies deep strata (deepslate gradient, optional bedrock floor at the world bottom) and
  samples deep biomes.
- `gen/CubeGenAccess` — a `WorldGenLevel` adapter for feature placement; writes clip to generated
  cubes, exactly like vanilla clips feature writes to the current region.

Global worldgen mixins (inspired by and credited to the C2Chunks reference implementation):

- `YClampedGradientMixin` — re-baselines the band-anchored terrain gradients below y=-64 so
  vanilla's own cave branch (cheese caverns, entrances, spaghetti, pillars) stays active at depth.
- `AbsoluteVerticalAnchorMixin` + `CountPlacementMixin` / `RepeatingPlacementMixin` — depth-scale
  negative feature anchors and compensate placement counts, but only while the extension planner
  evaluates (vanilla band decoration is untouched, staying vanilla-exact).
- `NoiseBasedAquiferMixin` — deep aquifers keep their shapes but lava becomes water pockets/dry
  caves (vanilla floods everything below y=-54 with lava).
- `StateRuleMixin` — relocates the bedrock floor to the configured world bottom; the vanilla band
  floor at y=-64 becomes deepslate (configurable).
- `ClimateSamplerMixin` — maps deep biome sampling onto vanilla cave biomes.

### Deliberate limits

- Structures are vanilla-band-only (they're absolute-anchored; generating them in strata would
  produce broken carcasses anyway).
- Surface (heightmap) features don't repeat in strata (they anchor to the surface — this is also
  what vanilla would do with infinite depth).
- Structures and heightmap-anchored surface features stay in the vanilla band (they are absolute-
  or surface-anchored; repeating them would produce broken carcasses).
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
