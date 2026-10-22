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

## Ordered commits for B

1. **Cube-sized `ChunkAccess` view** plus a config flag `vanillaFeaturePass`
   (default off). Verify by comparing ore counts per stratum against the current
   tiling on a pinned seed; the tiling is the reference, and the pass must not sit
   more than a small factor away from it.
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
