# Reworking how the world below -64 is generated

Status: proposed, not started. This note exists so the reasoning and the measured
evidence survive, rather than living in one long conversation.

## The goal

Vanilla's own generation passes, running over the full height, the way a
CubicChunks-style world does it: one continuous noise field, structures and features
placed by vanilla's passes rather than reconstructed by this mod, caves that are the
same field on both sides of -64.

## What was learned the hard way

Every measurement below was taken in this dev environment and is reproducible from
`/cubicchunks bench`.

- **The band already is vanilla.** Vanilla's `NoiseBasedChunkGenerator` generates
  every column's band through its normal pipeline. The extension only takes over
  below `bandMinY`.
- **Caves already connect across the seam.** 36.5 voids per 1k in the band's deepslate
  layer against 36.9 in the first stratum below it. This has not been the problem.
- **The gradient was not the problem either.** It was clamped, then re-baselined to a
  constant, then made to descend to vanilla's own floor value (0.2.19). Per-stratum
  void moved from 71.0 to 69.8 at the seam and stayed erratic below it, because the 6D
  base noise and the spaghetti/noodle fields dominate the gradient by about an order
  of magnitude. Anything that tries to shape the deep world through the anchor alone
  is fighting the wrong term.
- **The structure machinery was the problem, repeatedly.** In order: geode span
  measurement made one placement look like a whole band (0.2.3); deep starts were
  registered through the structure manager while placement read
  `chunk.getAllStarts()`, so 21 starts and 7,420 translated pieces produced zero
  placements (0.2.16); and finally the deep world offered
  `minecraft:mineshaft=2582558 terralith:rubble_taiga=4840` and nothing else, because
  that is all the sampled band contained.
- **A beard does not keep caves out of structures.** `Beardifier.compute` blends
  terrain height relative to a structure's ground level (`dy = blockY - groundY`,
  signed). Applied with `groundLevelDelta = 0` to a shaft inside solid rock it
  *increased* void: 24 per 1k became 38, comparing the same 40 cubes that were bound
  for a structure, same seed, same candidate set. Vanilla leaving mineshafts
  unbearable is deliberate.
- **A warning of our own misled the investigation for several rounds.** "later passes
  of this shape are skipped" actually described dropping one position outside a tile
  index. Corrected in 0.2.11.

## The three designs

**A. Whole tall columns, no cubes.** Let vanilla's generator fill all 384 sections of
a chunk. Simplest conceptually; the band stays exactly vanilla for free. Costs about
16x the memory and disk per chunk and generates thousands of blocks no player will
see. Nothing becomes lazy again.

**B. Per-cube vanilla passes (C2-shaped).** Keep 16-block cubes, and for each cube run
vanilla's own passes against a cube-sized chunk view: `applyBiomeDecoration` for
features, carvers from the biome, structures through the structure manager at the
depth the cube occupies. This is the design the user asked for. Blockers to solve
first:
  - `applyBiomeDecoration(WorldGenLevel, ChunkAccess, StructureManager)` decorates
    every section of the chunk it is given. A cube-sized `ChunkAccess` view has to be
    built, and `CountPlacement`-family mixins already scale counts by the number of
    sections, so the two have to be reconciled rather than multiplied twice.
  - Structures are decided per column (`isStructureChunk` is purely horizontal) and
    their starts carry a height from the structure's own provider. To get them below
    the band, the provider must be evaluated against the configured height rather than
    the vanilla band, and `MineshaftStructure`-style providers pick a Y inside their
    own hardcoded range. This is the real work.
  - Cross-chunk piece placement and the beardifier have to keep working, which is what
    the current `structureRigidsFor` already does.

**C. Hybrid.** Keep vanilla's passes inside the band, and below it run vanilla's
feature pass per cube (B) while structures continue to come from band starts (today's
behaviour). Smaller change; does not fix structure variety.

## Blocker found while starting commit 1 (decisive, changes the shape of B)

`ChunkGenerator.applyBiomeDecoration` is not self-contained. Reading it in the 26.2
sources:

- line 334-340: it walks a 3x3 neighbourhood and calls
  `level.getChunk(chunkPos.x(), chunkPos.z())` for each of the nine columns. That is a
  **blocking chunk load inside decoration**. Calling this from this mod's cube
  generation path is precisely the pattern that produced a 60-second tick and a
  watchdog kill earlier in the project, so it cannot be called inline there.
- line 350-360: it also places **structures** - `structureManager.startsForStructure(
  sectionPos, structure).forEach(start -> start.placeInChunk(...))` - inside the same
  pass, per generation step. So vanilla's structure placement is not a separate call
  to make at depth; it happens inside decoration, against the writable chunk.

What this means for design B: the per-cube pass cannot simply be invoked while a cube
is being generated. It has to be *scheduled* the way vanilla schedules it - wait until
the column's neighbourhood is loaded, then decorate - which means cubes need a
"decorated" state, much like vanilla's chunk status pipeline has a DECORATE stage
that waits on neighbours. Concretely:

- a cube is generated (terrain) as today;
- it is queued for decoration, keyed by column;
- the tick loop decorates cubes whose 3x3 neighbourhood is fully loaded, using the
  tick-loop loading pattern that already works (0.2.14) rather than requesting a chunk
  from inside generation;
- a cube saved before decoration is decorated on next load, tracked per section.

That is a bigger change than "add a chunk view", and it is the honest price of using
vanilla's pass instead of emulating it. It also inherits vanilla's behaviour of
placing structures during decoration, which is what makes depth-aware structure
placement work at all.

## Ordered commits for B

1. **Deferred decoration.** Cubes are generated as today, then queued for
   decoration; the tick loop decorates a cube once its column and the eight
   neighbours are loaded, reusing the proven tick-loop loading path. Nothing calls
   `applyBiomeDecoration` from inside generation. Ships behind `vanillaFeaturePass`
   (default off) so the existing tiling remains the fallback.
2. **Cube-sized `ChunkAccess` view** for the pass, plus reconciling it with the
   `CountPlacement`-family mixins so counts are scaled once and not twice. Verify by
   comparing ore counts per stratum against the current tiling on a pinned seed.
2. **Retire the feature tiling and the ore pool** once (1) matches, leaving vanilla's
   placement as the only path. Delete `placeCubeFeatures`, `measureFeatureSpan`,
   `isOreStratumEligible`, `oreFeaturesInDimension` and the `CountPlacement` scaling
   mixins if nothing else needs them.
3. **Depth-aware structure placement**: extend the structures whose start providers
   are bottom-relative so they can start anywhere in the configured range, behind a
   flag. Verify with `/cubicchunks bench`, whose report already names every structure
   that reaches the deep world and counts pieces placed.
4. **Delete the copying machinery**: `DeepStructures.createDeepStarts`, the
   transplant path, `transplantCache`, and the deep-start counters. The report gains
   a line saying structures come from vanilla placement.
5. **Revisit storage**: 16-block cubes remain the unit; nothing about (B) requires a
   tall `LevelChunk`.

## What must not regress

- The vanilla band stays byte-identical to vanilla within `[bandMinY, bandMaxY)`.
- The seam at `-64` keeps its void density.
- No blocking chunk loads inside chunk generation (that produced a 60-second tick and
  a watchdog kill); the tick-loop source loading from 0.2.14 is the pattern.
- The light engine must not be told about sections it has no storage for.

## The deeper reason structures cannot simply "run below -64" (found while scoping)

Vanilla has no notion of a structure at depth. A structure start's height comes from its
own provider - `Structure.findGenerationPoint(...)` resolving a
`HeightProvider` against the height accessor - and those providers are written for a
world whose floor is y=-64: bottom-relative means "just above -64", not "somewhere in
four thousand blocks". `applyBiomeDecoration` then places the starts vanilla already
decided on, at the heights vanilla chose.

This is why copying was the only thing that worked: below the band there are no vanilla
decisions to reproduce, only decisions to invent or translate. And it reframes the
rework: the unlock for structure variety is not a different pass, it is the *height
range vanilla generation is given*. A CubicChunks-style world works because
`above_bottom` means the bottom of an extended world, so bottom-relative structures
distribute over the whole depth without anyone copying anything.

Consequence for design B: commit 3 (depth-aware starts) is the load-bearing one, and it
is a settings/data change rather than a code path. The pass, the view and the
scheduler only matter once there is something to place.

Consequence for the vanilla band: any change to the height range vanilla sees moves
band-terrain anchors too, which is the one thing that must not regress. So the
experiment to run first is narrow: give the generator a taller world *for structure
start heights only* (a height accessor used by start providers that reports the
configured range), and check two things on a pinned seed - do structures appear at
depth, and is the band identical. If the band moves, the approach needs the start
providers offset per stratum instead, which is closer to what the transplant does.

Recommended next session, in order:
1. Spike: minimal `ChunkAccess` surface needed by `applyBiomeDecoration` (subclass
   `EmptyLevelChunk`, present a single section). Timebox it; if the surface is large,
   stop and reconsider, because 2 below may make it unnecessary.
2. Height-range experiment above, measured on a pinned seed with `/cubicchunks bench`
   and the per-stratum census.
3. Only then the deferred decoration scheduler.
