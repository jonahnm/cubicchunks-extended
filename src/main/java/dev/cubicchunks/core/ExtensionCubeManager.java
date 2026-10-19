package dev.cubicchunks.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import dev.cubicchunks.CubicChunks;
import dev.cubicchunks.CubicConfig;
import dev.cubicchunks.gen.CubeGenAccess;
import dev.cubicchunks.gen.ExtensionGenerator;
import dev.cubicchunks.worldgen.WorldGenState;
import dev.cubicchunks.CubicAttachments;
import dev.cubicchunks.worldgen.GeneratedSectionsData;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/**
 * Per-dimension driver for the vertical cubic world layer. All work happens on the server thread
 * within a per-tick budget so generation is fully deterministic.
 *
 * <p>Below each dimension's vanilla noise band, the world is streamed in as 16x16x16 cubes around
 * each player's vertical position. Columns themselves are still vanilla {@link LevelChunk}s, so
 * disk format, chunk lifecycle, lighting and modded worldgen in the vanilla band are untouched.
 */
public final class ExtensionCubeManager {

    private final ServerLevel level;
    private final CubicConfig.CubicLevelState state;
    private final ExtensionGenerator generator;

    /** Cubes generated this session (avoids re-noising restored-from-disk sections). */
    private final LongOpenHashSet generated = new LongOpenHashSet();
    /** Columns whose persisted generated-marker has been merged into {@link #generated}. */
    private final Set<ChunkPos> importedColumns = new HashSet<>();
    /** Columns that need a full re-send (extension cubes changed after initial packet). */
    private final Set<ChunkPos> resendQueue = new HashSet<>();

    private final Set<Class<?>> complainedFeatureFailures = new HashSet<>();
    /**
     * Native Y span of each height-anchored feature, measured on first use and cached; see
     * {@link #measureFeatureSpan}. Keyed by the feature instance, which is the same object for
     * every column and cube, so one measurement serves the whole world.
     */
    private java.util.Map<PlacedFeature, Integer> vminOfFeature =
            new java.util.HashMap<>();
    private java.util.Map<PlacedFeature, Integer> spanOfFeature =
            new java.util.HashMap<>();
    /** Full-column packets are expensive (193 sections); cap per tick to protect the tick budget. */
    private static final int MAX_RESENDS_PER_TICK = 12;

    // ---- bulk verification state (debug) ----
    private java.util.Iterator<ChunkPos> verifyQueue;
    private int verifyBlocks;
    private int verifyFeatureDiffs;
    private int verifyTerrainMismatches;
    private int verifyCubes;
    private int verifyColumnsDone;

    /**
     * Debug: verifies every generated cube of (2*radius+1)^2 columns against a full-column
     * extended NoiseChunk reference, one column per server tick (the reference build costs
     * ~1-2s per column). Results are logged as they complete plus an aggregate at the end.
     */
    public void startVerifyAll(int radius, ChunkPos center) {
        java.util.List<ChunkPos> columns = new java.util.ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                columns.add(new ChunkPos(center.x() + dx, center.z() + dz));
            }
        }
        this.verifyQueue = columns.iterator();
        this.verifyBlocks = 0;
        this.verifyFeatureDiffs = 0;
        this.verifyTerrainMismatches = 0;
        this.verifyCubes = 0;
        this.verifyColumnsDone = 0;
        CubicChunks.LOGGER.info("[CubicChunks] bulk verification started: {} columns around {}",
                columns.size(), center);
    }

    private void tickVerification() {
        if (verifyQueue == null) {
            return;
        }
        if (!verifyQueue.hasNext()) {
            CubicChunks.LOGGER.info(
                    "[CubicChunks] BULK VERIFY DONE: {} columns, {} cubes, {} blocks checked; {} feature-placed diffs (expected), {} TERRAIN mismatches",
                    verifyColumnsDone, verifyCubes, verifyBlocks, verifyFeatureDiffs, verifyTerrainMismatches);
            verifyQueue = null;
            return;
        }
        ChunkPos cp = verifyQueue.next();
        LevelChunk chunk = (LevelChunk) level.getChunk(cp.x(), cp.z(),
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
        ensureColumnImported(cp, chunk);
        it.unimi.dsi.fastutil.ints.IntOpenHashSet sections = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        for (int sy = generator.bandMinSection() - 1; sy >= state.minSection(); sy--) {
            if (generated.contains(SectionPos.asLong(cp.x(), sy, cp.z()))) {
                sections.add(sy);
            }
        }
        var result = generator.verifyAllCubesAgainstFullColumn(chunk, sections);
        verifyColumnsDone++;
        verifyCubes += result.cubes();
        verifyBlocks += result.blocks();
        verifyFeatureDiffs += result.featureDiffs();
        verifyTerrainMismatches += result.terrainMismatches();
        CubicChunks.LOGGER.info("[CubicChunks] verify column [{}, {}]: {}", cp.x(), cp.z(), result.summary());
    }

    public ExtensionCubeManager(ServerLevel level, CubicConfig.CubicLevelState state) {
        this.level = level;
        this.state = state;
        this.generator = new ExtensionGenerator(level, state);
    }

    public ServerLevel level() {
        return level;
    }

    public CubicConfig.CubicLevelState state() {
        return state;
    }

    public ExtensionGenerator generator() {
        return generator;
    }

    public int generatedCount() {
        return generated.size();
    }

    /**
     * Persisted marker: which section indexes of this column were generated by this mod. Restored
     * cubes are skipped by the streamer so player modifications survive restarts; unmarked deep
     * sections (carver residue, or worlds made before this marker existed) are regenerated.
     */
    /**
     * Merges a column's persisted generated-marker (chunk data attachment) into the session set,
     * once per column per session. Columns with no marker (older saves) fall back to the
     * "already looks like finished deep terrain" heuristic so player builds are not re-noised.
     */
    private void ensureColumnImported(ChunkPos pos, LevelChunk chunk) {
        if (!importedColumns.add(pos)) {
            return;
        }
        GeneratedSectionsData existing = chunk.getExistingDataOrNull(CubicAttachments.GENERATED_SECTIONS);
        if (existing != null && existing.bits().length > 0) {
            applyGeneratedBitset(pos, existing.bits());
        } else {
            markLikelyGeneratedFromChunk(pos, chunk);
        }
        persistGeneratedMarker(pos, chunk);
    }

    /** Writes the column's marker back onto the chunk so the chunk serializer persists it. */
    private void persistGeneratedMarker(ChunkPos pos, LevelChunk chunk) {
        long[] bits = generatedBitsetFor(pos);
        if (bits == null) {
            return;
        }
        chunk.getData(CubicAttachments.GENERATED_SECTIONS).setBits(bits);
        chunk.markUnsaved();
    }

    public long[] generatedBitsetFor(ChunkPos pos) {
        int sections = state.sectionsCount();
        long[] bits = new long[(sections + 63) >> 6];
        boolean any = false;
        for (int i = 0; i < sections; i++) {
            if (generated.contains(SectionPos.asLong(pos.x(), state.minSection() + i, pos.z()))) {
                bits[i >> 6] |= 1L << (i & 63);
                any = true;
            }
        }
        return any ? bits : null;
    }

    /** Applies a persisted marker bitset (section indexes relative to the world floor). */
    public void applyGeneratedBitset(ChunkPos pos, long[] bits) {
        int sections = Math.min(state.sectionsCount(), bits.length << 6);
        for (int i = 0; i < sections; i++) {
            if ((bits[i >> 6] & (1L << (i & 63))) != 0) {
                generated.add(SectionPos.asLong(pos.x(), state.minSection() + i, pos.z()));
            }
        }
    }

    /**
     * Fallback for worlds saved by older builds (no marker in NBT): mark deep sections that look
     * like fully-generated terrain (mostly solid stone/deepslate). Mostly-air sections (carver
     * residue) stay unmarked and will be regenerated.
     */
    public void markLikelyGeneratedFromChunk(ChunkPos pos, LevelChunk chunk) {
        for (int sy = generator.bandMinSection() - 1; sy >= state.minSection(); sy--) {
            long key = SectionPos.asLong(pos.x(), sy, pos.z());
            if (generated.contains(key)) {
                continue;
            }
            int idx = chunk.getSectionIndexFromSectionY(sy);
            LevelChunkSection[] sections = chunk.getSections();
            if (idx < 0 || idx >= sections.length || sections[idx].hasOnlyAir()) {
                continue;
            }
            LevelChunkSection section = sections[idx];
            int solidTerrain = 0;
            int nonAir = 0;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState st = section.getBlockState(x, y, z);
                        if (!st.isAir()) {
                            nonAir++;
                            var b = st.getBlock();
                            if (b == net.minecraft.world.level.block.Blocks.STONE
                                    || b == net.minecraft.world.level.block.Blocks.DEEPSLATE
                                    || b == net.minecraft.world.level.block.Blocks.BEDROCK) {
                                solidTerrain++;
                            }
                        }
                    }
                }
            }
            if (nonAir > 2048 && solidTerrain > nonAir / 2) {
                generated.add(key); // looks like fully generated deep terrain (possibly player-edited)
            }
        }
    }

    /** Debug: ensures the extension cube containing {@code y} in this column exists. */
    public boolean debugEnsureCube(ChunkPos colPos, int y) {
        int sy = SectionPos.blockToSectionCoord(y);
        if (sy >= generator.bandMinSection() || sy < state.minSection()) {
            return false;
        }
        return considerCube(SectionPos.asLong(colPos.x(), sy, colPos.z()));
    }

    /**
     * Rebuilds the terrain of every extension cube known to be generated, in place: the fresh cube
     * is merged over the installed one so only blocks vanilla generation could have produced are
     * replaced - player-placed blocks, and the interior of structures that a plain terrain rebuild
     * would fill solid, survive. Cubes of columns that are not loaded now are left untouched.
     * @param center restrict the rebuild to this many columns around here, or pass a negative
     *     radius for every column
     * @return the number of cubes rebuilt
     */
    public int regenerateGeneratedCubes(ChunkPos center, int radius) {
        var keys = new java.util.ArrayList<>(generated);
        int rebuilt = 0;
        for (long key : keys) {
            int cx = SectionPos.x(key);
            int cz = SectionPos.z(key);
            if (radius >= 0 && Math.max(Math.abs(cx - center.x()), Math.abs(cz - center.z())) > radius) {
                continue;
            }
            int sy = SectionPos.y(key);
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) {
                continue;
            }
            ensureColumnImported(chunk.getPos(), chunk);
            int idx = chunk.getSectionIndexFromSectionY(sy);
            LevelChunkSection[] sections = chunk.getSections();
            if (idx < 0 || idx >= sections.length) {
                continue;
            }
            generateAndApply(chunk, idx, sy, sections[idx]);
            rebuilt++;
        }
        return rebuilt;
    }

    /** Test aid: synchronously generate {@code depth} extension cubes below spawn (ring of columns). */
    public void debugPregenAroundSpawn(int depth, int radius) {
        ChunkPos spawn = ChunkPos.containing(level.getRespawnData().pos());
        int zoneTop = generator.bandMinSection() - 1;
        int done = 0;
        long startNanos = System.nanoTime();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos colPos = new ChunkPos(spawn.x() + dx, spawn.z() + dz);
                LevelChunk chunk = (LevelChunk) level.getChunk(colPos.x(), colPos.z(),
                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
                ensureColumnImported(colPos, chunk);
                for (int sy = zoneTop; sy > zoneTop - depth && sy >= state.minSection(); sy--) {
                    long key = SectionPos.asLong(colPos.x(), sy, colPos.z());
                    if (generated.contains(key)) {
                        continue;
                    }
                    int idx = chunk.getSectionIndexFromSectionY(sy);
                    if (idx < 0 || idx >= chunk.getSections().length) {
                        continue;
                    }
                    LevelChunkSection previous = chunk.getSections()[idx];
                    boolean wasEmpty = previous.hasOnlyAir();
                    generated.add(key);
                    generateAndApply(chunk, idx, sy, wasEmpty ? null : previous);
                    done++;
                }
            }
        }
        flushResends();
        CubicChunks.LOGGER.info("[CubicChunks] pre-generated {} extension cubes (radius {}) in {} ms",
                done, radius, (System.nanoTime() - startNanos) / 1_000_000);
        debugReportCubeComposition(spawn);
    }

    /**
     * Test aid: reports stone/deepslate/air composition of the generated extension cubes in this
     * column (verifies that vanilla density caves actually carved the deep world).
     */
    public void debugReportCubeComposition(ChunkPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
        ensureColumnImported(pos, chunk);
        if (chunk == null) {
            return;
        }
        int[] air = new int[2], solid = new int[2], fluid = new int[2], lava = new int[2],
                other = new int[2], ore = new int[2];
        int[] cubes = new int[2];
        int nearFloorStart = state.minSection() + CubicConfig.LAVA_NEAR_FLOOR_DEPTH.get() / 16 + 1;
        for (int sy = generator.bandMinSection() - 1; sy >= state.minSection(); sy--) {
            long key = SectionPos.asLong(pos.x(), sy, pos.z());
            if (!generated.contains(key)) {
                continue;
            }
            int idx = chunk.getSectionIndexFromSectionY(sy);
            if (idx < 0 || idx >= chunk.getSections().length) {
                continue;
            }
            LevelChunkSection section = chunk.getSections()[idx];
            int zone = sy >= nearFloorStart ? 0 : 1; // 0 = above the near-floor zone, 1 = near-floor lava zone
            cubes[zone]++;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        var state = section.getBlockState(x, y, z);
                        if (state.isAir()) {
                            air[zone]++;
                        } else if (state.is(net.minecraft.world.level.block.Blocks.LAVA)) {
                            lava[zone]++;
                        } else if (!state.getFluidState().isEmpty()) {
                            fluid[zone]++;
                        } else if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().endsWith("_ore")) {
                            // counted apart from "other": tells whether the tiled ore ranges actually
                            // reached this cube, which is what the band clipping used to hide
                            ore[zone]++;
                        } else if (state.is(net.minecraft.world.level.block.Blocks.DEEPSLATE)
                                || state.is(net.minecraft.world.level.block.Blocks.STONE)
                                || state.is(net.minecraft.world.level.block.Blocks.BEDROCK)) {
                            solid[zone]++;
                        } else {
                            other[zone]++;
                        }
                    }
                }
            }
        }
        for (int zone = 1; zone >= 0; zone--) {
            int total = air[zone] + solid[zone] + fluid[zone] + lava[zone] + other[zone] + ore[zone];
            if (total > 0) {
                CubicChunks.LOGGER.info(
                        "[CubicChunks] cube composition in {} [{}] ({} cubes): {}% air, {}% stone/deepslate/bedrock, {}% water, {}% lava, {}% ore, {}% other",
                        pos, zone == 0 ? "above the near-floor lava zone" : "near-floor lava zone", cubes[zone],
                        air[zone] * 100 / total, solid[zone] * 100 / total,
                        fluid[zone] * 100 / total, lava[zone] * 100 / total, ore[zone] * 100 / total, other[zone] * 100 / total);
            }
        }
    }

    // ---------------------------------------------------------------- streaming tick

    private final java.util.ArrayDeque<Long> workQueue = new java.util.ArrayDeque<>();

    private static double distanceSq(ChunkPos center, ChunkPos other) {
        long dx = (long) other.x() - center.x();
        long dz = (long) other.z() - center.z();
        return dx * dx + dz * dz;
    }

    public void serverTick() {
        // 2-tick cadence amortizes the scan; the work budget smooths spikes.
        if (this.level.getServer().getTickCount() % 2 == 0) {
            buildWorkQueue();
        }

        // Safety net: the cube directly below each player's feet must exist before they can
        // fall into it, even if the budgeted streamer hasn't caught up yet.
        emergencyCatchUp();
        tickVerification();

        int budget = CubicConfig.GENERATION_PER_TICK.get();
        while (budget > 0 && !workQueue.isEmpty()) {
            long cubeKey = workQueue.pollFirst();
            budget -= considerCube(cubeKey) ? 1 : 0;
        }

        flushResends();
    }

    /** Desired below-band cubes, nearest-first around each player's eye level. */
    private void buildWorkQueue() {
        workQueue.clear();
        int vert = CubicConfig.VERTICAL_LOAD_DISTANCE.get();
        int zoneTop = generator.bandMinSection() - 1;

        for (ServerPlayer player : level.getPlayers(p -> true)) {
            ChunkTrackingView view = player.getChunkTrackingView();
            if (view == ChunkTrackingView.EMPTY) {
                continue;
            }
            int eye = SectionPos.blockToSectionCoord(Mth.floor(player.getY()));
            // Below the player we stream deeper than above: digging/falling must never outrun
            // generation, and pre-generating a margin below makes deep descents seamless.
            int belowDist = vert + FALL_MARGIN_CUBES;
            int yMax = Math.min(eye + vert, zoneTop);
            int yMin = Math.max(eye - belowDist, state.minSection());
            if (yMin > yMax) {
                continue;
            }
            final int yMinF = yMin;
            final int yMaxF = yMax;
            final int eyeF = eye;
            final int belowDistF = belowDist;
            final int vertF = vert;
            // Nearest columns first so cubes materialize around the player, not at the fringes.
            List<ChunkPos> columns = new ArrayList<>();
            view.forEach(columns::add);
            ChunkPos playerChunk = player.chunkPosition();
            columns.sort((a, b) -> Double.compare(
                    distanceSq(playerChunk, a), distanceSq(playerChunk, b)));
            for (ChunkPos colPos : columns) {
                for (int dy = 0; dy <= belowDistF + vertF; dy++) {
                    int below = eyeF - dy;
                    if (below >= yMinF && below <= yMaxF) {
                        workQueue.addLast(SectionPos.asLong(colPos.x(), below, colPos.z()));
                    }
                    if (dy > 0 && dy <= vertF) {
                        int above = eyeF + dy;
                        if (above >= yMinF && above <= yMaxF) {
                            workQueue.addLast(SectionPos.asLong(colPos.x(), above, colPos.z()));
                        }
                    }
                }
            }
        }
    }

    /**
     * Generates and applies the cube for {@code cubeKey} if still needed.
     * @return whether generation actually ran (consumes tick budget)
     */
    private boolean considerCube(long cubeKey) {
        if (generated.contains(cubeKey)) {
            return false;
        }
        int cx = SectionPos.x(cubeKey);
        int sy = SectionPos.y(cubeKey);
        int cz = SectionPos.z(cubeKey);

        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) {
            return false; // column not loaded yet; retried next scan
        }
        ensureColumnImported(chunk.getPos(), chunk);
        if (generated.contains(cubeKey)) {
            return false; // the restored marker says: already generated
        }
        int idx = chunk.getSectionIndexFromSectionY(sy);
        LevelChunkSection[] sections = chunk.getSections();
        if (idx < 0 || idx >= sections.length) {
            return false;
        }

        // NOTE: hasOnlyAir() is NOT trusted as "already generated" — vanilla carvers write
        // residue (cave air, dirt) into still-empty deep sections during column generation,
        // which would leave mostly-air holes below the band. Unmarked sections are regenerated
        // (surgically preserving foreign/player blocks); marked ones are mine and skipped.
        boolean wasEmpty = sections[idx].hasOnlyAir();

        generated.add(cubeKey); // mark BEFORE features so the cube can receive its own writes
        generateAndApply(chunk, idx, sy, wasEmpty ? null : sections[idx]);
        return true;
    }

    // ---------------------------------------------------------------- cube generation

    /**
     * Blocks that vanilla generation (noise, carvers) can legitimately leave in an ungenerated
     * deep section. Anything else in an unmarked section is preserved during regeneration
     * (player-placed blocks survive a terrain regen).
     */
    private static final java.util.Set<net.minecraft.world.level.block.Block> VANILLA_DEEP_BLOCKS = java.util.Set.of(
            net.minecraft.world.level.block.Blocks.AIR,
            net.minecraft.world.level.block.Blocks.CAVE_AIR,
            net.minecraft.world.level.block.Blocks.VOID_AIR,
            net.minecraft.world.level.block.Blocks.STONE,
            net.minecraft.world.level.block.Blocks.DEEPSLATE,
            net.minecraft.world.level.block.Blocks.BEDROCK,
            net.minecraft.world.level.block.Blocks.DIRT,
            net.minecraft.world.level.block.Blocks.GRAVEL,
            net.minecraft.world.level.block.Blocks.GRANITE,
            net.minecraft.world.level.block.Blocks.DIORITE,
            net.minecraft.world.level.block.Blocks.ANDESITE,
            net.minecraft.world.level.block.Blocks.TUFF,
            net.minecraft.world.level.block.Blocks.WATER,
            net.minecraft.world.level.block.Blocks.LAVA);

    /** Installs the freshly generated cube section, preserving foreign blocks from the old one. */
    private void generateAndApply(LevelChunk chunk, int idx, int sectionY, LevelChunkSection previous) {
        LevelChunkSection section = generator.generateCube(chunk, sectionY);
        if (previous != null) {
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState old = previous.getBlockState(x, y, z);
                        if (!old.isAir() && !VANILLA_DEEP_BLOCKS.contains(old.getBlock())) {
                            section.setBlockState(x, y, z, old, false);
                        }
                    }
                }
            }
        }
        chunk.getSections()[idx] = section;
        chunk.markUnsaved();

        // Features always run: the cube's own (deep, remapped) biome drives its feature set.
        Holder<Biome> cubeBiome = section.getBiomes().get(1, 1, 1);
        placeCubeFeatures(chunk, section, sectionY, cubeBiome);

        // Deep structure pieces (registered by DeepStructures) re-place themselves into the fresh
        // section, including their interior air - terrain regen alone would fill rooms solid.
        placeStructurePieces(chunk, sectionY);

        SectionPos sectionPos = SectionPos.of(chunk.getPos(), sectionY);
        // notify the light engine: new non-empty (or emptier) section
        level.getChunkSource().getLightEngine().updateSectionStatus(sectionPos, section.hasOnlyAir());

        markResend(chunk.getPos());
        persistGeneratedMarker(chunk.getPos(), chunk);
    }

    /**
     * Re-runs structure piece placement for pieces intersecting this cube. Structure interiors
     * place AIR explicitly, which the surgical terrain regen cannot preserve - so pieces are
     * re-applied on top of the fresh terrain, clipped to the cube.
     *
     * <p>The pieces of a column are indexed by section Y once, then reused for every cube of that
     * column: asking {@link StructureManager} for starts (and scanning every piece of every start)
     * for each cube would load neighbour chunks and cost cubes x pieces.
     */
    private void placeStructurePieces(LevelChunk chunk, int sectionY) {
        if (!CubicConfig.DEEP_STRUCTURES.get()) {
            return;
        }
        Map<Integer, java.util.List<StructurePiece>> index = pieceIndexForColumn(chunk);
        java.util.List<StructurePiece> pieces = index.get(sectionY);
        if (pieces == null) {
            return;
        }
        ChunkPos chunkPos = chunk.getPos();
        int cubeMinY = sectionY << 4;
        BoundingBox cubeBox = new BoundingBox(
                chunkPos.getMinBlockX(), cubeMinY, chunkPos.getMinBlockZ(),
                chunkPos.getMinBlockX() + 15, cubeMinY + 15, chunkPos.getMinBlockZ() + 15);
        var structureManager = level.structureManager();
        for (StructurePiece piece : pieces) {
            if (!piece.getBoundingBox().intersects(cubeBox)) {
                continue;
            }
            RandomSource rand = RandomSource.create(level.getSeed()
                    ^ sectionY * 1000003587L ^ chunkPos.pack()
                    ^ System.identityHashCode(piece));
            try {
                piece.postProcess(pieceAccess(chunk), structureManager, level.getChunkSource().getGenerator(),
                        rand, cubeBox, chunkPos,
                        new net.minecraft.core.BlockPos(chunkPos.getMinBlockX(), cubeMinY, chunkPos.getMinBlockZ()));
            } catch (Throwable t) {
                if (complainedFeatureFailures.add(piece.getClass())) {
                    CubicChunks.LOGGER.warn("[CubicChunks] deep piece placement failed (suppressing repeats): {}",
                            t.toString());
                }
            }
        }
    }

    /** Section Y -> pieces, built from the starts of this and any already-loaded neighbour column. */
    private Map<Integer, java.util.List<StructurePiece>> pieceIndexForColumn(LevelChunk chunk) {
        ChunkPos origin = chunk.getPos();
        long columnKey = origin.pack();
        if (indexedColumn == columnKey) {
            return pieceIndex;
        }
        java.util.Map<Integer, java.util.List<StructurePiece>> index =
                new java.util.HashMap<Integer, java.util.List<StructurePiece>>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                // loaded columns only: never trigger a chunk load from the tick loop
                LevelChunk col = level.getChunkSource().getChunkNow(origin.x() + dx, origin.z() + dz);
                if (col == null) {
                    continue;
                }
                for (StructureStart start : col.getAllStarts().values()) {
                    if (start == null || !start.isValid()) {
                        continue;
                    }
                    for (StructurePiece piece : start.getPieces()) {
                        BoundingBox bb = piece.getBoundingBox();
                        // BoundingBox keeps its bounds private; the corners give the Y span.
                        int[] ySpan = {Integer.MAX_VALUE, Integer.MIN_VALUE};
                        bb.forAllCorners(pos -> {
                            if (pos.getY() < ySpan[0]) {
                                ySpan[0] = pos.getY();
                            }
                            if (pos.getY() > ySpan[1]) {
                                ySpan[1] = pos.getY();
                            }
                        });
                        for (int sy = SectionPos.blockToSectionCoord(ySpan[0]);
                                sy <= SectionPos.blockToSectionCoord(ySpan[1]); sy++) {
                            index.computeIfAbsent(sy, s -> new java.util.ArrayList<StructurePiece>()).add(piece);
                        }
                    }
                }
            }
        }
        indexedColumn = columnKey;
        pieceIndex = index;
        return index;
    }

    private long indexedColumn = Long.MIN_VALUE;
    private Map<Integer, java.util.List<StructurePiece>> pieceIndex = java.util.Map.of();

    private dev.cubicchunks.gen.CubeGenAccess pieceAccess;

    private dev.cubicchunks.gen.CubeGenAccess pieceAccess(LevelChunk chunk) {
        if (pieceAccess == null) {
            pieceAccess = new dev.cubicchunks.gen.CubeGenAccess(level, this, generator.bandMinY(), generator.stratumHeight());
        }
        return pieceAccess;
    }

    private void markResend(ChunkPos pos) {
        resendQueue.add(pos);
    }

    /** Extra cubes streamed below each player's eye, so descents never outrun generation. */
    private static final int FALL_MARGIN_CUBES = 4;

    /**
     * Guarantee: the cube directly below each player's feet always exists (unless the player's
     * own body cube would be filled, which would suffocate them). Runs synchronously, outside the
     * generation budget — this is what prevents falling into ungenerated void while digging down.
     */
    private void emergencyCatchUp() {
        for (ServerPlayer player : level.getPlayers(p -> true)) {
            int bodyY = Mth.floor(player.getY());
            int bodyCube = SectionPos.blockToSectionCoord(bodyY);
            if (bodyCube >= generator.bandMinSection() || bodyCube < state.minSection()) {
                continue; // not in the extension zone
            }
            int belowCube = SectionPos.blockToSectionCoord(bodyY - 1);
            if (belowCube >= generator.bandMinSection()) {
                belowCube = bodyCube - 1; // strictly below the body cube
            }
            if (belowCube < state.minSection()) {
                continue;
            }
            long key = SectionPos.asLong(player.chunkPosition().x(), belowCube, player.chunkPosition().z());
            if (!generated.contains(key)) {
                considerCube(key);
            }
        }
    }

    private void flushResends() {
        if (resendQueue.isEmpty()) {
            return;
        }
        var lightEngine = level.getChunkSource().getLightEngine();
        List<ServerPlayer> players = null;
        int budget = MAX_RESENDS_PER_TICK;
        java.util.Iterator<ChunkPos> it = resendQueue.iterator();
        while (it.hasNext() && budget > 0) {
            ChunkPos cp = it.next();
            it.remove();
            LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x(), cp.z());
            if (chunk == null) {
                continue;
            }
            budget--;
            ClientboundLevelChunkWithLightPacket packet =
                    new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null);
            if (players == null) {
                players = level.getPlayers(p -> true);
            }
            for (ServerPlayer player : players) {
                if (player.getChunkTrackingView().contains(cp)) {
                    player.connection.send(packet);
                }
            }
        }
    }

    // ---------------------------------------------------------------- feature plumbing (called from CubeGenAccess)

    /** Whether a feature write at {@code pos} lands in a generated extension cube. */
    public boolean cubeCanAcceptWrite(BlockPos pos) {
        if (pos.getY() >= generator.bandMinY() || pos.getY() < state.minY()) {
            return false;
        }
        int cubeY = pos.getY() >> 4;
        long cubeKey = SectionPos.asLong(pos.getX() >> 4, cubeY, pos.getZ() >> 4);
        if (!generated.contains(cubeKey)) {
            return false;
        }
        // 26.x places ore blobs straight into the section it acquires (BulkSectionAccess), so the
        // write never passes through setBlock: flag the owning column here, or the feature blocks
        // would neither be saved nor re-sent to clients.
        LevelChunk target = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (target != null) {
            target.markUnsaved();
            markResend(target.getPos());
        }
        return true;
    }

    public boolean cubeSetBlockFromFeature(BlockPos pos, net.minecraft.world.level.block.state.BlockState blockState) {
        if (pos.getY() >= generator.bandMinY() || pos.getY() < state.minY()) {
            return false;
        }
        int cubeY = pos.getY() >> 4;
        long cubeKey = SectionPos.asLong(pos.getX() >> 4, cubeY, pos.getZ() >> 4);
        if (!generated.contains(cubeKey)) {
            return false; // clip to generated cubes, like vanilla clips to write radius
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return false;
        }
        int idx = chunk.getSectionIndexFromSectionY(cubeY);
        if (idx < 0 || idx >= chunk.getSections().length) {
            return false;
        }
        chunk.getSections()[idx].setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, blockState, false);
        if (blockState.hasBlockEntity()) {
            // entity blocks (monster-room spawners, chests...) need their BlockEntity registered,
            // otherwise they are inert after save/reload
            var blockEntity = ((net.minecraft.world.level.block.EntityBlock) blockState.getBlock())
                    .newBlockEntity(pos, blockState);
            if (blockEntity != null) {
                chunk.setBlockEntity(blockEntity);
            }
        }
        chunk.markUnsaved();
        markResend(chunk.getPos());
        return true;
    }

    // ---------------------------------------------------------------- cube feature planning

    /**
     * Places the underground features (vanilla and modded biome features with height-anchored
     * placement; ores, veins, ...) for the freshly generated cube.
     *
     * <p>Model: heights are sampled with VANILLA-exact distributions against the vanilla band
     * (no anchor scaling). The feature's native span is measured once per feature and cached, and
     * that range is tiled downward by it, so the space below the band is covered continuously
     * rather than only where vanilla's own ranges happen to dip. A vanilla pass scatters its blobs
     * across the whole band height, so each tile's count is scaled by span/band-height to hold the
     * per-block density at the band's, then multiplied by
     * (1 + stratum * oreDepthBonusPerStratum) where stratum counts band heights below the band:
     * ore probability grows with depth. RNG is seeded per (column, cube, feature, tile) - unique
     * per seed, never repeating.
     */
    private void placeCubeFeatures(LevelChunk chunk, LevelChunkSection section, int sectionY, Holder<Biome> cubeBiome) {
        int cubeMinY = sectionY << 4;
        int cubeMaxY = cubeMinY + 16;
        ChunkPos chunkPos = chunk.getPos();
        CubeGenAccess access = new CubeGenAccess(level, this, generator.bandMinY(), generator.stratumHeight());
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        long seed = level.getSeed();
        double bonus = CubicConfig.ORE_DEPTH_BONUS.get();

        List<net.minecraft.core.HolderSet<PlacedFeature>> steps = cubeBiome.value().getGenerationSettings().features();
        int stepIndex = 0;
        for (net.minecraft.core.HolderSet<PlacedFeature> stepFeatures : steps) {
            int innerIndex = 0;
            for (Holder<PlacedFeature> holder : stepFeatures) {
                PlacedFeature placed = holder.value();
                if (!isStratumEligible(placed)) {
                    innerIndex++;
                    continue;
                }
                // lava policy: vanilla lava springs must not place lava outside the near-floor zone
                if (isLavaSpring(holder) && cubeMinY > lavaZoneTop()) {
                    innerIndex++;
                    continue;
                }
                try {
                    PlacementContext context = new PlacementContext(access, gen, Optional.of(placed));
                    long featureSeed = seed + chunkPos.pack() + sectionY * 1000003587L
                            + stepIndex * 10000019L + innerIndex * 34187003L;

                    // the feature's native span is a property of its placement chain only - measure
                    // it once, then reuse: tiling by the native span is what keeps the per-block
                    // density below the band equal to the band's own density
                    if (!spanOfFeature.containsKey(placed)) {
                        long measured = measureFeatureSpan(placed, context, chunkPos);
                        spanOfFeature.put(placed, (int) measured);
                        vminOfFeature.put(placed, (int) (measured >> 32));
                    }
                    int span = spanOfFeature.get(placed);
                    if (span == 0) {
                        innerIndex++;
                        continue;
                    }
                    int vmin = vminOfFeature.get(placed);

                    // one tile row per cube: the native range tiled downward, so the region under
                    // the band is covered continuously rather than only where vanilla's ranges dip
                    int tMax = Math.min(
                            Math.floorDiv(vmin + span - cubeMinY, span),
                            Math.floorDiv(vmin + span - state.minY(), span));
                    int tMin = Math.max(0, Math.floorDiv(vmin - cubeMaxY, span) + 1);
                    if (tMin > tMax) {
                        innerIndex++;
                        continue;
                    }
                    // A vanilla pass scatters its blobs over the whole band height; tiling them by
                    // the native span would therefore multiply density by band height / span. Scale
                    // the count back by that ratio so depth starts at band density, then grow it per
                    // stratum below the band (oreDepthBonusPerStratum).
                    int stratum = Math.max(0,
                            Math.floorDiv(generator.bandMinY() - cubeMaxY, generator.stratumHeight()));
                    double scale = (double) span / (double) generator.stratumHeight();
                    double multiplier = scale * (1.0 + stratum * bonus);
                    for (int tile = tMin; tile <= tMax; tile++) {
                        RandomSource rand = RandomSource.create(featureSeed ^ (tile * 0x9E3779B97F4A7C15L));
                        Stream<BlockPos> stream =
                                Stream.of(new BlockPos(chunkPos.getMinBlockX(), generator.bandMinY(), chunkPos.getMinBlockZ()));
                        WorldGenState.CURRENT_MULTIPLIER.set(multiplier);
                        try {
                            for (PlacementModifier modifier : placed.placement()) {
                                stream = stream.flatMap(pos -> modifier.getPositions(context, rand, pos));
                            }
                            final int shift = tile * span;
                            stream.filter(pos -> {
                                int y = pos.getY() - shift;
                                return y >= cubeMinY && y < cubeMaxY && y >= state.minY()
                                        && y < generator.bandMinY();
                            }).forEach(pos -> {
                                BlockPos target = pos.offset(0, -shift, 0);
                                try {
                                    holder.value().feature().value().place(access, gen, rand, target);
                                } catch (Throwable t) {
                                    if (complainedFeatureFailures.add(
                                            holder.value().feature().value().getClass())) {
                                        CubicChunks.LOGGER.warn(
                                                "[CubicChunks] deep feature placement failed (suppressing repeats): {}",
                                                t.toString());
                                    }
                                }
                            });
                        } catch (Throwable t) {
                            if (complainedFeatureFailures.add(placed.getClass())) {
                                CubicChunks.LOGGER.warn(
                                        "[CubicChunks] deep feature planning failed (suppressing repeats): {}",
                                        t.toString());
                            }
                        } finally {
                            WorldGenState.CURRENT_MULTIPLIER.set(1.0);
                        }
                    }
                } catch (Throwable t) {
                    if (complainedFeatureFailures.add(placed.getClass())) {
                        CubicChunks.LOGGER.warn(
                                "[CubicChunks] deep feature planning failed (suppressing repeats): {}",
                                t.toString());
                    }
                }
                innerIndex++;
            }
            stepIndex++;
        }
    }

    // ---------------------------------------------------------------- feature span measurement

    /**
     * Native vertical span of a height-anchored feature, measured once per feature and cached
     * (it depends only on the feature's own placement chain). The span doubles as the tiling period,
     * so measuring it properly - rather than reading one roll of the placement - is what keeps ore
     * density below the band comparable to the band's own. Counts are amplified while measuring so
     * the sampled envelope covers the distribution instead of a single draw of it.
     * @return the native span packed into the low half with the top of the range in the high half,
     *     or 0 when the feature places nowhere reachable
     */
    private long measureFeatureSpan(PlacedFeature placed, PlacementContext context, ChunkPos chunkPos) {
        int[] bounds = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        RandomSource rand = RandomSource.create(0x5EEDF00DL);
        Stream<BlockPos> stream = Stream.of(
                new BlockPos(chunkPos.getMinBlockX(), generator.bandMinY(), chunkPos.getMinBlockZ()));
        WorldGenState.CURRENT_MULTIPLIER.set(32.0);
        try {
            for (PlacementModifier modifier : placed.placement()) {
                stream = stream.flatMap(pos -> modifier.getPositions(context, rand, pos));
            }
            stream.forEach(pos -> {
                int y = pos.getY();
                if (y < bounds[0]) {
                    bounds[0] = y;
                }
                if (y > bounds[1]) {
                    bounds[1] = y;
                }
            });
        } finally {
            WorldGenState.CURRENT_MULTIPLIER.set(1.0);
        }
        int vmin = bounds[0];
        int vmax = bounds[1];
        if (vmax < vmin || vmax < state.minY()) {
            return 0L;
        }
        int span = Math.max(16, vmax - vmin);
        return ((long) vmin << 32) | (long) span;
    }

    private static int lavaZoneTop() {
        Integer floor = CubicConfig.extensionMinY();
        return floor == null ? Integer.MIN_VALUE : floor + CubicConfig.LAVA_NEAR_FLOOR_DEPTH.get();
    }

    private static boolean isLavaSpring(Holder<PlacedFeature> holder) {
        return holder.unwrapKey()
                .map(k -> k.identifier().equals(net.minecraft.resources.Identifier.withDefaultNamespace("spring_lava")))
                .orElse(false);
    }

    /** Underground features only: height-anchored placements, not surface/heightmap-anchored. */
    private static boolean isStratumEligible(PlacedFeature placed) {
        boolean anchored = false;
        for (PlacementModifier modifier : placed.placement()) {
            if (modifier instanceof HeightmapPlacement) {
                return false;
            }
            if (modifier instanceof HeightRangePlacement) {
                anchored = true;
            }
        }
        return anchored;
    }

}
