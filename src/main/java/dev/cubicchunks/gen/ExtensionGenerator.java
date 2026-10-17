package dev.cubicchunks.gen;

import java.util.Arrays;

import dev.cubicchunks.CubicConfig;
import dev.cubicchunks.worldgen.WorldGenState;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Generates one 16x16x16 extension cube below the vanilla band of a dimension.
 *
 * <p>The content is pure vanilla machinery: the world's own {@link RandomState} (identical noise
 * instances to vanilla worldgen) evaluated through a per-cube {@link NoiseChunk} with a
 * cube-sized {@link NoiseSettings} window, over the same absolute coordinates a full-height
 * vanilla generation would use. Cave shaping comes from vanilla's own density functions — the
 * {@code YClampedGradientMixin} re-baselines the band-anchored terrain gradients at depth so the
 * vanilla {@code underground} cave branch (cheese caverns, entrances, spaghetti, pillars) stays
 * active below the band, exactly as it does in the reference C2Chunks implementation. No custom
 * noise is added.
 *
 * <p>Surface-rule equivalents for depth (deepslate gradient, optional bedrock floor at the world
 * bottom) are applied by {@link #applyStrataRule}; the band's own bedrock is relocated by the
 * {@code StateRuleMixin}.
 *
 * <p>For non-noise generators (flat worlds, exotic modded engines) a simple strata fallback is
 * used.
 */
public final class ExtensionGenerator {

    /** No-op beardifier (no structures exist in extension cubes). */
    private enum NoBeardifier implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            return 0.0;
        }

        @Override
        public void fillArray(double[] array, DensityFunction.ContextProvider provider) {
            Arrays.fill(array, 0.0);
        }

        @Override
        public double minValue() {
            return 0.0;
        }

        @Override
        public double maxValue() {
            return 0.0;
        }
    }

    /** Exposes {@link NoiseChunk#getInterpolatedState()} (protected) for our cube fill loop. */
    private static final class CubeNoiseChunk extends NoiseChunk {
        CubeNoiseChunk(int cellCountXZ, RandomState randomState, int blockX, int blockZ,
                NoiseSettings noiseSettings, DensityFunctions.BeardifierOrMarker beardifier,
                NoiseGeneratorSettings genSettings, Aquifer.FluidPicker fluidPicker) {
            super(cellCountXZ, randomState, blockX, blockZ, noiseSettings,
                    beardifier, genSettings, fluidPicker, Blender.empty());
        }

        BlockState nextBlockState() {
            return getInterpolatedState();
        }
    }

    private final ServerLevel level;
    private final CubicConfig.CubicLevelState state;
    private final ChunkGenerator generator;

    /** Y range where vanilla worldgen produced terrain: [bandMinY, bandMaxY). */
    private final int bandMinY;
    private final int bandMaxY;
    private final int stratumHeight;

    /** Non-null only when the dimension's generator is noise-based. */
    private final NoiseGeneratorSettings settings;
    private final RandomState randomState;
    private final Aquifer.FluidPicker fluidPicker;
    private final boolean deepslateStrata;

    public ExtensionGenerator(ServerLevel level, CubicConfig.CubicLevelState state) {
        this.level = level;
        this.state = state;
        this.generator = level.getChunkSource().getGenerator();

        if (generator instanceof NoiseBasedChunkGenerator nbg) {
            this.settings = nbg.generatorSettings().value();
            NoiseSettings ns = this.settings.noiseSettings();
            this.bandMinY = ns.minY();
            this.bandMaxY = ns.minY() + ns.height();
            this.stratumHeight = ns.height();
            this.randomState = level.getChunkSource().randomState();
            this.fluidPicker = createFluidPicker(this.settings);
            this.deepslateStrata = this.settings.defaultBlock() == Blocks.STONE.defaultBlockState();
        } else {
            this.settings = null;
            DimensionType type = level.dimensionType();
            this.bandMinY = type.minY();
            this.bandMaxY = type.minY() + type.height();
            this.stratumHeight = Math.max(16, type.height());
            this.randomState = null;
            this.fluidPicker = null;
            this.deepslateStrata = !type.ultraWarm();
        }
    }

    public int bandMinY() {
        return bandMinY;
    }

    public int bandMaxY() {
        return bandMaxY;
    }

    public int bandMinSection() {
        return bandMinY >> 4;
    }

    public int stratumHeight() {
        return stratumHeight;
    }

    /**
     * Generates the extension cube (16x16x16) at {@code sectionY} of the given column and returns
     * it; the caller installs it into the chunk.
     */
    public LevelChunkSection generateCube(LevelChunk chunk, int sectionY) {
        int cubeMinY = sectionY << 4;
        ChunkPos chunkPos = chunk.getPos();

        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        PalettedContainer<BlockState> states = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        PalettedContainer<Holder<Biome>> biomes = new PalettedContainer<>(
                biomeRegistry.asHolderIdMap(),
                biomeRegistry.getHolderOrThrow(Biomes.PLAINS),
                PalettedContainer.Strategy.SECTION_BIOMES);

        if (settings != null) {
            fillFromVanillaNoise(chunk, cubeMinY, states);
        } else {
            fillFallback(cubeMinY, states);
        }
        fillBiomes(cubeMinY, chunkPos, biomes);

        return new LevelChunkSection(states, biomes);
    }

    private void fillFromVanillaNoise(LevelChunk chunk, int cubeMinY, PalettedContainer<BlockState> states) {
        NoiseSettings base = settings.noiseSettings();
        NoiseSettings cubeSettings =
                new NoiseSettings(cubeMinY, 16, base.noiseSizeHorizontal(), base.noiseSizeVertical());
        NoiseGeneratorSettings cubeGenSettings = new NoiseGeneratorSettings(
                cubeSettings,
                settings.defaultBlock(),
                settings.defaultFluid(),
                settings.noiseRouter(),
                settings.surfaceRule(),
                settings.spawnTarget(),
                settings.seaLevel(),
                settings.disableMobGeneration(),
                settings.isAquifersEnabled(),
                settings.oreVeinsEnabled(),
                settings.useLegacyRandomSource());

        ChunkPos chunkPos = chunk.getPos();
        // real Beardifier: terrain adaptation (beard) around deep structure pieces
        var beardifier = net.minecraft.world.level.levelgen.Beardifier.forStructuresInChunk(
                level.structureManager(), chunkPos);
        CubeNoiseChunk noiseChunk = new CubeNoiseChunk(
                16 / cubeSettings.getCellWidth(),
                randomState,
                chunkPos.getMinBlockX(),
                chunkPos.getMinBlockZ(),
                cubeSettings,
                beardifier,
                cubeGenSettings,
                fluidPicker);

        // Replicates NoiseBasedChunkGenerator#doFill, restricted to this cube's 16-block window.
        // Identical to a full-height vanilla fill because interpolation cells are globally aligned.
        int cellWidth = cubeSettings.getCellWidth();
        int cellHeight = cubeSettings.getCellHeight();
        int cellsXZ = 16 / cellWidth;
        int cellsY = Math.floorDiv(16, cellHeight);
        int firstCellY = Math.floorDiv(cubeMinY, cellHeight);

        noiseChunk.initializeForFirstCellX();
        for (int cx = 0; cx < cellsXZ; cx++) {
            noiseChunk.advanceCellX(cx);
            for (int cz = 0; cz < cellsXZ; cz++) {
                for (int cy = cellsY - 1; cy >= 0; cy--) {
                    noiseChunk.selectCellYZ(cy, cz);
                    for (int inCellY = cellHeight - 1; inCellY >= 0; inCellY--) {
                        int absY = (firstCellY + cy) * cellHeight + inCellY;
                        noiseChunk.updateForY(absY, (double) inCellY / (double) cellHeight);
                        for (int inCellX = 0; inCellX < cellWidth; inCellX++) {
                            int absX = chunkPos.getMinBlockX() + cx * cellWidth + inCellX;
                            noiseChunk.updateForX(absX, (double) inCellX / (double) cellWidth);
                            for (int inCellZ = 0; inCellZ < cellWidth; inCellZ++) {
                                int absZ = chunkPos.getMinBlockZ() + cz * cellWidth + inCellZ;
                                noiseChunk.updateForZ(absZ, (double) inCellZ / (double) cellWidth);
                                BlockState block = noiseChunk.nextBlockState();
                                if (block == null) {
                                    block = settings.defaultBlock();
                                }
                                if (block.isAir()) {
                                    continue;
                                }
                                if (block.getFluidState().isEmpty()) {
                                    block = applyStrataRule(absX, absY, absZ, block);
                                    if (block.isAir()) {
                                        continue;
                                    }
                                }
                                states.set(inCellX + cx * cellWidth, absY & 15, inCellZ + cz * cellWidth, block);
                            }
                        }
                    }
                }
            }
            noiseChunk.swapSlices();
        }
        noiseChunk.stopInterpolation();
    }

    /**
     * Depth strata for extension cubes, mirroring vanilla's surface rules: deepslate gradient
     * below y=8 (for stone-based dimensions) and an optional noisy bedrock floor at the world
     * bottom. The band's own bedrock is handled by {@code StateRuleMixin} during vanilla surface.
     */
    private BlockState applyStrataRule(int x, int y, int z, BlockState block) {
        if (block == settings.defaultBlock() && deepslateStrata) {
            if (y <= 0) {
                block = Blocks.DEEPSLATE.defaultBlockState();
            } else if (y < 8 && hash01(x, y, z) < (8.0 - y) / 8.0) {
                block = Blocks.DEEPSLATE.defaultBlockState();
            }
        }
        if (CubicConfig.DEEP_BEDROCK_FLOOR.get() && block == Blocks.DEEPSLATE.defaultBlockState()) {
            int floor = this.state.minY();
            if (y <= floor + 4) {
                double fade = (floor + 4.0 - y) / 4.0; // 1 at floor, 0 at floor+4
                if (y == floor || hash01(x, y, z) < fade) {
                    block = Blocks.BEDROCK.defaultBlockState();
                }
            }
        }
        return block;
    }

    /** Fallback for dimensions without a NoiseBasedChunkGenerator: vanilla-ish strata only. */
    private void fillFallback(int cubeMinY, PalettedContainer<BlockState> states) {
        boolean netherLike = level.dimensionType().ultraWarm();
        BlockState base = netherLike ? Blocks.NETHERRACK.defaultBlockState() : Blocks.STONE.defaultBlockState();
        BlockState deep = netherLike ? Blocks.NETHERRACK.defaultBlockState() : Blocks.DEEPSLATE.defaultBlockState();
        for (int y = 0; y < 16; y++) {
            int absY = cubeMinY + y;
            BlockState layer = absY <= 0 ? deep : base;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    states.set(x, y, z, layer);
                }
            }
        }
    }

    /**
     * Biomes are sampled at the cube's real (deep) Y position. While sampling, the climate-sampler
     * remap is active so deep coordinates resolve to vanilla cave biomes (deterministic per seed).
     */
    private void fillBiomes(int cubeMinY, ChunkPos chunkPos, PalettedContainer<Holder<Biome>> biomes) {
        BiomeSource source = generator.getBiomeSource();
        Climate.Sampler sampler = randomState != null ? randomState.sampler() : null;
        if (sampler == null) {
            return; // plains default everywhere; only features/spawns care
        }
        boolean remap = CubicConfig.REMAP_DEEP_BIOMES.get();
        WorldGenState.REMAP_DEEP_BIOMES.set(remap);
        try {
            for (int qx = 0; qx < 4; qx++) {
                for (int qz = 0; qz < 4; qz++) {
                    int qX = QuartPos.fromBlock(chunkPos.getMinBlockX() + qx * 4 + 2);
                    int qZ = QuartPos.fromBlock(chunkPos.getMinBlockZ() + qz * 4 + 2);
                    for (int qy = 0; qy < 4; qy++) {
                        int sampleY = QuartPos.fromBlock(cubeMinY + qy * 4 + 2);
                        Holder<Biome> biome = source.getNoiseBiome(qX, sampleY, qZ, sampler);
                        biomes.set(qx, qy, qz, biome);
                    }
                }
            }
        } finally {
            WorldGenState.REMAP_DEEP_BIOMES.set(false);
        }
    }

    /**
     * Debug: regenerates the cube at {@code sectionY} through a FULL-COLUMN extended NoiseChunk
     * (vanilla's exact path over the whole modded range) and diffs it against the installed
     * per-cube section. Proves (or disproves) that per-cube generation matches full-column.
     */
    public String verifyAgainstFullColumn(LevelChunk chunk, int sectionY) {
        int cubeMinY = sectionY << 4;
        ChunkPos chunkPos = chunk.getPos();
        NoiseSettings base = settings.noiseSettings();
        NoiseSettings fullSettings = new NoiseSettings(
                state.minY(), state.maxY() - state.minY(),
                base.noiseSizeHorizontal(), base.noiseSizeVertical());
        NoiseGeneratorSettings fullGenSettings = new NoiseGeneratorSettings(
                fullSettings,
                settings.defaultBlock(),
                settings.defaultFluid(),
                settings.noiseRouter(),
                settings.surfaceRule(),
                settings.spawnTarget(),
                settings.seaLevel(),
                settings.disableMobGeneration(),
                settings.isAquifersEnabled(),
                settings.oreVeinsEnabled(),
                settings.useLegacyRandomSource());
        CubeNoiseChunk noiseChunk = new CubeNoiseChunk(
                16 / fullSettings.getCellWidth(),
                randomState,
                chunkPos.getMinBlockX(),
                chunkPos.getMinBlockZ(),
                fullSettings,
                NoBeardifier.INSTANCE,
                fullGenSettings,
                fluidPicker);

        int cellWidth = fullSettings.getCellWidth();
        int cellHeight = fullSettings.getCellHeight();
        int cellsXZ = 16 / cellWidth;
        int cellsY = Math.floorDiv(fullSettings.height(), cellHeight);
        int firstCellY = Math.floorDiv(fullSettings.minY(), cellHeight);

        LevelChunkSection installed = chunk.getSections()[chunk.getSectionIndexFromSectionY(sectionY)];
        int mismatches = 0;
        int checked = 0;
        String firstSamples = "";

        noiseChunk.initializeForFirstCellX();
        for (int cx = 0; cx < cellsXZ; cx++) {
            noiseChunk.advanceCellX(cx);
            for (int cz = 0; cz < cellsXZ; cz++) {
                for (int cy = cellsY - 1; cy >= 0; cy--) {
                    int cellBaseY = (firstCellY + cy) * cellHeight;
                    boolean inCube = cellBaseY + cellHeight > cubeMinY && cellBaseY < cubeMinY + 16;
                    noiseChunk.selectCellYZ(cy, cz);
                    int yStart = inCube ? Math.max(cellHeight - 1, cubeMinY + 15 - cellBaseY > cellHeight - 1 ? cellHeight - 1 : cubeMinY + 15 - cellBaseY) : -1;
                    for (int inCellY = cellHeight - 1; inCellY >= 0; inCellY--) {
                        int absY = cellBaseY + inCellY;
                        boolean blockInCube = absY >= cubeMinY && absY < cubeMinY + 16;
                        if (!blockInCube) {
                            // still drive the interpolator state for correctness
                            noiseChunk.updateForY(absY, (double) inCellY / (double) cellHeight);
                            for (int inCellX = 0; inCellX < cellWidth; inCellX++) {
                                noiseChunk.updateForX(chunkPos.getMinBlockX() + cx * cellWidth + inCellX,
                                        (double) inCellX / (double) cellWidth);
                                for (int inCellZ = 0; inCellZ < cellWidth; inCellZ++) {
                                    noiseChunk.updateForZ(chunkPos.getMinBlockZ() + cz * cellWidth + inCellZ,
                                            (double) inCellZ / (double) cellWidth);
                                }
                            }
                            continue;
                        }
                        noiseChunk.updateForY(absY, (double) inCellY / (double) cellHeight);
                        for (int inCellX = 0; inCellX < cellWidth; inCellX++) {
                            int absX = chunkPos.getMinBlockX() + cx * cellWidth + inCellX;
                            noiseChunk.updateForX(absX, (double) inCellX / (double) cellWidth);
                            for (int inCellZ = 0; inCellZ < cellWidth; inCellZ++) {
                                int absZ = chunkPos.getMinBlockZ() + cz * cellWidth + inCellZ;
                                noiseChunk.updateForZ(absZ, (double) inCellZ / (double) cellWidth);
                                BlockState expected = noiseChunk.nextBlockState();
                                if (expected == null) {
                                    expected = settings.defaultBlock();
                                }
                                if (expected.isAir()) {
                                    continue;
                                }
                                if (expected.getFluidState().isEmpty()) {
                                    expected = applyStrataRule(absX, absY, absZ, expected);
                                    if (expected.isAir()) {
                                        continue;
                                    }
                                }
                                BlockState actual = installed.getBlockState(
                                        absX & 15, absY & 15, absZ & 15);
                                if (actual.isAir() && expected.is(Blocks.CAVE_AIR)) {
                                    continue; // aquifer air conversion equivalence
                                }
                                checked++;
                                if (actual.getBlock() != expected.getBlock()) {
                                    mismatches++;
                                    if (mismatches <= 5) {
                                        firstSamples += String.format("[%d %d %d] exp=%s got=%s; ",
                                                absX, absY, absZ,
                                                expected.getBlock(), actual.getBlock());
                                    }
                                }
                            }
                        }
                    }
                }
            }
            noiseChunk.swapSlices();
        }
        noiseChunk.stopInterpolation();
        return String.format("cube %s @y=%d: checked %d blocks, %d mismatches. %s",
                chunkPos, cubeMinY, checked, mismatches, firstSamples);
    }

    /** Aggregate result of a full-column verification. */
    public record ColumnVerifyResult(int cubes, int blocks, int featureDiffs, int terrainMismatches, String samples) {
        public String summary() {
            return String.format("%d cubes / %d blocks: %d feature-placed diffs (expected), %d TERRAIN mismatches %s",
                    cubes, blocks, featureDiffs, terrainMismatches,
                    terrainMismatches == 0 ? "(per-cube == full-column)" : "SAMPLES: " + samples);
        }
    }

    /**
     * Debug: builds a full-column extended NoiseChunk (vanilla's exact path over the whole modded
     * range) once, then diffs EVERY generated cube of this column against its slice. Blocks placed
     * by the deep feature planner (ores, springs) are expected differences and counted separately.
     */
    public java.util.Map<String, Integer> lastMismatchHistogram = new java.util.TreeMap<>();

    private static boolean isFeatureBlockId(String key) {
        String id = key.substring(0, key.lastIndexOf('@'));
        return id.endsWith("_ore") || id.equals("gravel") || id.equals("dirt") || id.equals("granite")
                || id.equals("diorite") || id.equals("andesite") || id.equals("tuff") || id.equals("sculk")
                || id.equals("sculk_vein") || id.equals("glow_lichen") || id.equals("clay")
                || id.equals("moss_block") || id.equals("mossy_cobblestone") || id.equals("lava")
                || id.equals("water") || id.equals("sculk_catalyst")
                || id.equals("dripstone_block") || id.equals("pointed_dripstone")
                || id.equals("big_dripleaf") || id.equals("big_dripleaf_stem")
                || id.equals("small_dripleaf") || id.equals("cave_vines")
                || id.equals("cave_vines_plant") || id.equals("spore_blossom")
                || id.equals("smooth_basalt") || id.equals("amethyst_block") || id.equals("calcite")
                || id.equals("cobblestone") || id.equals("cave_air") || id.equals("spawner")
                || id.equals("budding_amethyst") || id.equals("amethyst_cluster")
                || id.equals("large_amethyst_bud") || id.equals("medium_amethyst_bud")
                || id.equals("small_amethyst_bud") || id.equals("bloated_amethyst")
                || id.equals("hollow_tuff") || id.equals("tuff") || id.equals("chest");
    }

    public ColumnVerifyResult verifyAllCubesAgainstFullColumn(LevelChunk chunk, it.unimi.dsi.fastutil.ints.IntSet generatedSections) {
        java.util.Map<String, Integer> mismatchBlocks = new java.util.TreeMap<>();
        this.lastMismatchHistogram = mismatchBlocks;
        ChunkPos chunkPos = chunk.getPos();
        NoiseSettings base = settings.noiseSettings();
        NoiseSettings fullSettings = new NoiseSettings(
                state.minY(), state.maxY() - state.minY(),
                base.noiseSizeHorizontal(), base.noiseSizeVertical());
        NoiseGeneratorSettings fullGenSettings = new NoiseGeneratorSettings(
                fullSettings,
                settings.defaultBlock(),
                settings.defaultFluid(),
                settings.noiseRouter(),
                settings.surfaceRule(),
                settings.spawnTarget(),
                settings.seaLevel(),
                settings.disableMobGeneration(),
                settings.isAquifersEnabled(),
                settings.oreVeinsEnabled(),
                settings.useLegacyRandomSource());
        CubeNoiseChunk noiseChunk = new CubeNoiseChunk(
                16 / fullSettings.getCellWidth(),
                randomState,
                chunkPos.getMinBlockX(),
                chunkPos.getMinBlockZ(),
                fullSettings,
                NoBeardifier.INSTANCE,
                fullGenSettings,
                fluidPicker);

        int cellWidth = fullSettings.getCellWidth();
        int cellHeight = fullSettings.getCellHeight();
        int cellsXZ = 16 / cellWidth;
        int cellsY = Math.floorDiv(fullSettings.height(), cellHeight);
        int firstCellY = Math.floorDiv(fullSettings.minY(), cellHeight);

        int cubes = 0;
        int checked = 0;
        int featureDiffs = 0;
        int terrainMismatches = 0;
        StringBuilder samples = new StringBuilder();

        noiseChunk.initializeForFirstCellX();
        for (int cx = 0; cx < cellsXZ; cx++) {
            noiseChunk.advanceCellX(cx);
            for (int cz = 0; cz < cellsXZ; cz++) {
                for (int cy = cellsY - 1; cy >= 0; cy--) {
                    int cellBaseY = (firstCellY + cy) * cellHeight;
                    noiseChunk.selectCellYZ(cy, cz);
                    for (int inCellY = cellHeight - 1; inCellY >= 0; inCellY--) {
                        int absY = cellBaseY + inCellY;
                        int sectionY = absY >> 4;
                        boolean compare = generatedSections.contains(sectionY);
                        noiseChunk.updateForY(absY, (double) inCellY / (double) cellHeight);
                        for (int inCellX = 0; inCellX < cellWidth; inCellX++) {
                            int absX = chunkPos.getMinBlockX() + cx * cellWidth + inCellX;
                            noiseChunk.updateForX(absX, (double) inCellX / (double) cellWidth);
                            for (int inCellZ = 0; inCellZ < cellWidth; inCellZ++) {
                                int absZ = chunkPos.getMinBlockZ() + cz * cellWidth + inCellZ;
                                noiseChunk.updateForZ(absZ, (double) inCellZ / (double) cellWidth);
                                if (!compare) {
                                    continue;
                                }
                                BlockState expected = noiseChunk.nextBlockState();
                                if (expected == null) {
                                    expected = settings.defaultBlock();
                                }
                                if (expected.isAir()) {
                                    continue;
                                }
                                if (expected.getFluidState().isEmpty()) {
                                    expected = applyStrataRule(absX, absY, absZ, expected);
                                    if (expected.isAir()) {
                                        continue;
                                    }
                                }
                                LevelChunkSection section = chunk.getSections()[chunk.getSectionIndexFromSectionY(sectionY)];
                                BlockState actual = section.getBlockState(absX & 15, absY & 15, absZ & 15);
                                if (actual.isAir() && expected.is(Blocks.CAVE_AIR)) {
                                    continue;
                                }
                                checked++;
                                if (actual.getBlock() == expected.getBlock()) {
                                    continue;
                                }
                                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(actual.getBlock()).getPath();
                                if (isFeatureBlockId(id + "@" + sectionY) || actual.is(Blocks.LAVA)
                                        || actual.is(Blocks.WATER)) {
                                    featureDiffs++;
                                } else {
                                    terrainMismatches++;
                                    if (terrainMismatches <= 8) {
                                        samples.append(String.format("[%d %d %d] exp=%s got=%s; ",
                                                absX, absY, absZ, expected.getBlock(), actual.getBlock()));
                                    }
                                }
                                mismatchBlocks.merge(id + "@" + sectionY, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
            noiseChunk.swapSlices();
        }
        noiseChunk.stopInterpolation();

        // For sections with terrain mismatches: regenerate the cube fresh (pure per-cube math)
        // and compare fresh-vs-installed to see whether the installed content drifted afterwards.
        if (terrainMismatches > 0) {
            for (int sectionY : generatedSections) {
                int mismatchesInSection = 0;
                for (var e : mismatchBlocks.entrySet()) {
                    if (e.getKey().endsWith("@" + sectionY) && !isFeatureBlockId(e.getKey())) {
                        mismatchesInSection += e.getValue();
                    }
                }
                if (mismatchesInSection == 0) {
                    continue;
                }
                LevelChunkSection fresh = generateCube(chunk, sectionY);
                int freshVsInstalled = 0;
                for (int lx = 0; lx < 16; lx++) {
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            BlockState f = fresh.getBlockState(lx, ly, lz);
                            BlockState inst = chunk.getSections()[chunk.getSectionIndexFromSectionY(sectionY)]
                                    .getBlockState(lx, ly, lz);
                            if (f.getBlock() != inst.getBlock()) {
                                freshVsInstalled++;
                            }
                        }
                    }
                }
                dev.cubicchunks.CubicChunks.LOGGER.info(
                        "[CubicChunks] regen check section {} of {}: fresh-vs-installed diffs = {} (0 = installed was mutated after generation; >0 = per-cube math diverges from full-column)",
                        sectionY, chunkPos, freshVsInstalled);
            }
        }

        cubes = generatedSections.size();
        StringBuilder histogram = new StringBuilder();
        for (var e : mismatchBlocks.entrySet()) {
            histogram.append(e.getKey()).append(" x").append(e.getValue()).append("; ");
        }
        return new ColumnVerifyResult(cubes, checked, featureDiffs, terrainMismatches,
                samples + " HISTOGRAM: " + histogram);
    }

    /** Vanilla {@code NoiseBasedChunkGenerator#createFluidPicker}, replicated. */
    private static Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int seaLevel = settings.seaLevel();
        Aquifer.FluidStatus water = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, seaLevel) ? lava : water;
    }

    /** Deterministic per-position pseudo-random in [0, 1). */
    private static double hash01(int x, int y, int z) {
        long h = x * 0x8F3DA95BL ^ y * 0xC2B2AE35L ^ z * 0x27D4EB2FL;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h = Long.rotateLeft(h, 21) * 0x9FB21C651E98DF25L;
        h ^= h >>> 29;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }
}
