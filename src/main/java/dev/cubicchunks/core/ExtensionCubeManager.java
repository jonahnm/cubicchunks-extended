package dev.cubicchunks.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;


import dev.cubicchunks.CubicChunks;
import dev.cubicchunks.CubicConfig;
import dev.cubicchunks.gen.CubeGenAccess;
import dev.cubicchunks.gen.ExtensionGenerator;
import dev.cubicchunks.worldgen.WorldGenState;
import dev.cubicchunks.CubicAttachments;
import dev.cubicchunks.worldgen.GeneratedSectionsData;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
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
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
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
    /**
     * Per-column bookkeeping for what a changed column still owes the world: a save and a resend.
     *
     * <p>Both are whole-column operations in vanilla. Saving a column re-copies every one of its
     * sections and light layers ({@code SerializableChunkData.copyOf} runs on the server thread),
     * and a chunk packet carries the whole column too - so doing either once per generated cube is
     * what made deep exploration degrade with depth: each further cube made every later save of
     * that column more expensive. A column is therefore only handed to vanilla once its generation
     * has gone quiet, and columns far from any player are refreshed lazily instead of per cube.
     */
    private static final int SAVE_QUIET_TICKS = 20;
    private static final int SEND_QUIET_TICKS = 20;
    private static final int WORK_DEFER_LIMIT_TICKS = 100;
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap columnLastTouched =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet pendingSave =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet pendingSend =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /** Columns whose persisted generated-marker has been merged into {@link #generated}. */
    private final Set<ChunkPos> importedColumns = new HashSet<>();

    /**
     * Debug: per-phase cost of one extension cube, bucketed by how far below the vanilla band it
     * is. Enabled only by {@link #debugPregenAroundSpawn}, which walks a controlled descent — the
     * numbers then say directly which part of cube generation grows with depth.
     */
    private static final int PHASE_NOISE = 0;
    private static final int PHASE_MERGE = 1;
    private static final int PHASE_FEATURES = 2;
    private static final int PHASE_STRUCTURES = 3;
    private static final int PHASE_TAIL = 4;
    private static final int PHASE_COUNT = 5;
    private static final int MAX_STRATA = 16;
    private boolean profileCost;
    private int profileStratum = -1;
    private long profileSends;
    private long profileSaveMarks;
    private final long[] profileNanos = new long[MAX_STRATA * PHASE_COUNT];
    private final long[] profileCubes = new long[MAX_STRATA];
    private final long[] profileCandidates = new long[MAX_STRATA];
    private final long[] profilePlaced = new long[MAX_STRATA];

    private int stratumOf(int sectionY) {
        int raw = Math.floorDiv(generator.bandMinY() - (sectionY << 4), Math.max(16, generator.stratumHeight()));
        return Math.max(0, Math.min(MAX_STRATA - 1, raw));
    }

    private void addPhase(int phase, int stratum, long nanos) {
        if (stratum >= 0) {
            this.profileNanos[stratum * PHASE_COUNT + phase] += nanos;
        }
    }

    private void markPhase(int phase, int stratum, long startedAt) {
        if (stratum >= 0) {
            this.profileNanos[stratum * PHASE_COUNT + phase] += System.nanoTime() - startedAt;
        }
    }

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
        this.profileCost = true;
        resetCostProfile();
        this.generator.enableCostProfile();
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
        flushColumnWork(true);
        CubicChunks.LOGGER.info("[CubicChunks] pre-generated {} extension cubes (radius {}) in {} ms",
                done, radius, (System.nanoTime() - startNanos) / 1_000_000);
        debugReportCubeComposition(spawn);
        logCostProfile();
        debugReportBiomeProfile(java.util.List.of(spawn));
        this.profileCost = false;
    }

    /**
     * Debug: walk a straight descent the way a falling player does and report what each depth
     * costs. Every generated cube marks its column dirty, and a re-send carries the WHOLE column
     * (all sections plus every client-sent heightmap array), so what a re-send costs grows with
     * how much of that column has already been filled - that is the quadratic part of deep
     * exploration, and this measures it directly.
     */
    public void debugDescentBench(int radius, int toSectionY) {
        ChunkPos spawn = ChunkPos.containing(level.getRespawnData().pos());
        java.util.List<ChunkPos> columns = new java.util.ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                columns.add(new ChunkPos(spawn.x() + dx, spawn.z() + dz));
            }
        }
        for (ChunkPos col : columns) {
            level.getChunk(col.x(), col.z(),
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
        }
        this.profileCost = true;
        this.benchViewer = spawn;
        resetCostProfile();
        int vert = CubicConfig.VERTICAL_LOAD_DISTANCE.get();
        for (int eye = generator.bandMinSection() - 1; eye >= toSectionY; eye -= 8) {
            java.util.ArrayDeque<Long> window = new java.util.ArrayDeque<>();
            collectDesiredCubes(spawn, eye, vert, columns, window);
            workQueue.clear();
            workQueue.addAll(window);
            long start = System.nanoTime();
            int cubes = 0;
            // Drain the window the way the tick loop does: runs of neighbouring cubes in one column,
            // capped by the configured per-tick budget.
            while (!workQueue.isEmpty()) {
                if (considerCube(workQueue.pollFirst())) {
                    cubes++;
                }
            }
            long genNanos = System.nanoTime() - start;
            long touched = pendingSend.size();
            this.profileSends = 0;
            this.profileSaveMarks = 0;
            long flushStart = System.nanoTime();
            flushColumnWork(false);
            long flushNanos = System.nanoTime() - flushStart;
            long[] unit = measureColumnUnitCost(spawn);
            CubicChunks.LOGGER.info(
                    "[CubicChunks] bench eye y {}: {} cubes generated in {} ms ({} /cube);"
                            + " columns changed this layer: {} (the old policy would re-send and"
                            + " re-save every one of them now); columns refreshed now: {} in {} ms;"
                            + " columns handed to the save path: {}; one column at this depth costs"
                            + " {} ms to send and {} ms to save ({} filled sections)",
                    eye << 4, cubes, genNanos / 1_000_000,
                    cubes == 0 ? "-" : fmtMs(genNanos, cubes),
                    touched, this.profileSends, flushNanos / 1_000_000, this.profileSaveMarks,
                    unit[0] / 1_000_000, unit[1] / 1_000_000, unit[2]);
        }
        this.benchViewer = null;
        logCostProfile();
        debugReportFeatureStats();
        debugReportBlockCensus(columns);
        debugColumnSaveBreakdown(spawn);
        debugReportBiomeProfile(columns);
        debugReportStructures(columns);
        debugReportFeatureStats();
        debugReportCubeComposition(spawn);
        this.profileCost = false;
    }

    /**
     * What one whole-column operation costs at the current depth: the chunk packet vanilla builds
     * for a re-send, and {@code SerializableChunkData.copyOf} - the copy of every section palette,
     * light layer and heightmap that {@code ChunkMap.save} does on the server thread.
     */
    private long[] measureColumnUnitCost(ChunkPos centre) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(centre.x(), centre.z());
        if (chunk == null) {
            return new long[] {0L, 0L, 0L};
        }
        var lightEngine = level.getChunkSource().getLightEngine();
        long start = System.nanoTime();
        new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null);
        long packetNanos = System.nanoTime() - start;
        start = System.nanoTime();
        net.minecraft.world.level.chunk.storage.SerializableChunkData.copyOf(level, chunk);
        long saveNanos = System.nanoTime() - start;
        long filled = 0;
        for (LevelChunkSection section : chunk.getSections()) {
            if (!section.hasOnlyAir()) {
                filled++;
            }
        }
        return new long[] {packetNanos, saveNanos, filled};
    }

    /**
     * Debug: split {@code SerializableChunkData.copyOf}'s work into the pieces it is made of, so
     * the part that grows with depth can be named instead of guessed at.
     */
    public void debugColumnSaveBreakdown(ChunkPos centre) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(centre.x(), centre.z());
        if (chunk == null) {
            CubicChunks.LOGGER.warn("[CubicChunks] save breakdown: column {} is not loaded", centre);
            return;
        }
        long[] times = new long[8];
        long[][] counts = new long[8][];
        long start = System.nanoTime();
        int hmTypes = 0;
        long hmLongs = 0;
        for (java.util.Map.Entry<net.minecraft.world.level.levelgen.Heightmap.Types,
                net.minecraft.world.level.levelgen.Heightmap> entry : chunk.getHeightmaps()) {
            if (chunk.getPersistedStatus().getChunkSaveHeightmaps().contains(entry.getKey())) {
                hmTypes++;
                hmLongs += entry.getValue().getRawData().length;
            }
        }
        times[0] = System.nanoTime() - start;
        counts[0] = new long[] {hmTypes, hmLongs};

        start = System.nanoTime();
        long copiedSections = 0;
        for (LevelChunkSection section : chunk.getSections()) {
            if (!section.hasOnlyAir()) {
                section.copy();
                copiedSections++;
            }
        }
        times[1] = System.nanoTime() - start;
        counts[1] = new long[] {copiedSections, chunk.getSections().length};

        start = System.nanoTime();
        var light = level.getChunkSource().getLightEngine();
        long lightSections = 0, lightCopies = 0;
        for (int sectionY = light.getMinLightSection(); sectionY < light.getMaxLightSection(); sectionY++) {
            lightSections++;
            for (net.minecraft.world.level.LightLayer layer
                    : net.minecraft.world.level.LightLayer.values()) {
                net.minecraft.world.level.chunk.DataLayer data =
                        light.getLayerListener(layer).getDataLayerData(SectionPos.of(centre, sectionY));
                if (data != null && !data.isEmpty()) {
                    data.copy();
                    lightCopies++;
                }
            }
        }
        times[2] = System.nanoTime() - start;
        counts[2] = new long[] {lightSections, lightCopies};

        start = System.nanoTime();
        chunk.getTicksForSerialization(level.getGameTime());
        times[3] = System.nanoTime() - start;

        start = System.nanoTime();
        chunk.getUpgradeData().copy();
        times[4] = System.nanoTime() - start;

        start = System.nanoTime();
        chunk.writeAttachmentsToNBT(level.registryAccess());
        times[5] = System.nanoTime() - start;

        start = System.nanoTime();
        long structurePieces = 0;
        int structureStarts = 0;
        for (StructureStart s : chunk.getAllStarts().values()) {
            if (s == null || !s.isValid()) {
                continue;
            }
            structureStarts++;
            structurePieces += s.getPieces().size();
        }
        times[6] = System.nanoTime() - start;
        counts[6] = new long[] {structureStarts, structurePieces};

        start = System.nanoTime();
        net.minecraft.world.level.chunk.storage.SerializableChunkData.copyOf(level, chunk);
        times[7] = System.nanoTime() - start;

        CubicChunks.LOGGER.info(
                "[CubicChunks] save breakdown for column {}: heightmaps {} types / {} longs = {} ms;"
                        + " section copies {} of {} = {} ms; light scan {} sections, {} copies = {} ms;"
                        + " tick packing {} ms; upgrade data {} ms; structure data {} starts / {} pieces"
                        + " = {} ms; attachments {} ms; the whole copyOf call {} ms",
                centre, hmTypes, hmLongs, ms(times[0]), copiedSections, chunk.getSections().length,
                ms(times[1]), lightSections, lightCopies, ms(times[2]), ms(times[3]), ms(times[4]),
                structureStarts, structurePieces, ms(times[6]), ms(times[5]), ms(times[7]));
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", (double) nanos / 1_000_000.0);
    }

    private static String fmtMs(long nanos, long count) {
        return String.format(java.util.Locale.ROOT, "%.1f", (double) nanos / 1_000_000.0 / (double) Math.max(1L, count));
    }

    private static boolean hasHeightmapPlacement(PlacedFeature placed) {
        for (PlacementModifier modifier : placementChain(placed)) {
            if (modifier instanceof HeightmapPlacement) {
                return true;
            }
        }
        return false;
    }

    private void resetCostProfile() {
        java.util.Arrays.fill(this.profileNanos, 0L);
        java.util.Arrays.fill(this.profileCubes, 0L);
        java.util.Arrays.fill(this.profileCandidates, 0L);
        java.util.Arrays.fill(this.profilePlaced, 0L);
        this.profileFeatureEvals = 0L;
        this.featureStats.clear();
        this.featureNotes.clear();
    }

    /** Debug: print the collected per-stratum generation cost profile. */
    public void logCostProfile() {
        int stratumHeight = Math.max(16, generator.stratumHeight());
        long totalCubes = 0L;
        for (long cubes : this.profileCubes) {
            totalCubes += cubes;
        }
        for (int s = 0; s < MAX_STRATA; s++) {
            long cubes = this.profileCubes[s];
            if (cubes == 0) {
                continue;
            }
            int topY = generator.bandMinY() - s * stratumHeight;
            CubicChunks.LOGGER.info(
                    "[CubicChunks] cost stratum {} (y {}..{}): {} cubes | per cube: noise {} ms, merge {} ms,"
                            + " features {} ms, structures {} ms, tail {} ms | feature candidates {},"
                            + " placed {}",
                    s, topY - stratumHeight, topY, cubes,
                    msPerCube(this.profileNanos[s * PHASE_COUNT + PHASE_NOISE], cubes),
                    msPerCube(this.profileNanos[s * PHASE_COUNT + PHASE_MERGE], cubes),
                    msPerCube(this.profileNanos[s * PHASE_COUNT + PHASE_FEATURES], cubes),
                    msPerCube(this.profileNanos[s * PHASE_COUNT + PHASE_STRUCTURES], cubes),
                    msPerCube(this.profileNanos[s * PHASE_COUNT + PHASE_TAIL], cubes),
                    fmtPerCube(this.profileCandidates[s], cubes),
                    fmtPerCube(this.profilePlaced[s], cubes));
            CubicChunks.LOGGER.info(
                    "[CubicChunks]   stratum {} placement: {} candidate positions read,"
                            + " {} passed to features, {} placement passes evaluated for {} cubes",
                    s, fmtPerCube(this.profileCandidates[s], cubes),
                    fmtPerCube(this.profilePlaced[s], cubes),
                    String.format(java.util.Locale.ROOT, "%.2f", (double) this.profileFeatureEvals / (double) Math.max(1L, totalCubes)),
                    cubes);
            long[] sub = this.generator.costProfileNanos();
            CubicChunks.LOGGER.info(
                    "[CubicChunks]   stratum {} cube internals: palettes {} ms, beardifier {} ms,"
                            + " noise-chunk {} ms, density fill {} ms, biomes {} ms",
                    s,
                    msPerCube(sub[s * ExtensionGenerator.SUB_COUNT + ExtensionGenerator.SUB_CONTAINERS], cubes),
                    msPerCube(sub[s * ExtensionGenerator.SUB_COUNT + ExtensionGenerator.SUB_BEARDIFIER], cubes),
                    msPerCube(sub[s * ExtensionGenerator.SUB_COUNT + ExtensionGenerator.SUB_NOISE_CHUNK], cubes),
                    msPerCube(sub[s * ExtensionGenerator.SUB_COUNT + ExtensionGenerator.SUB_FILL], cubes),
                    msPerCube(sub[s * ExtensionGenerator.SUB_COUNT + ExtensionGenerator.SUB_BIOMES], cubes));
        }
    }

    private static String fmtPerCube(long count, long cubes) {
        return String.format(java.util.Locale.ROOT, "%.1f", (double) count / (double) Math.max(1L, cubes));
    }

    private static double msPerCube(long nanos, long cubes) {
        return (double) nanos / 1_000_000.0 / (double) Math.max(1L, cubes);
    }

    /** Debug: which biomes the extension cubes actually hold, bucketed by depth. */
    /**
     * Debug: which biomes actually ended up at which depth, over several columns. Sampling a few
     * spots inside each section rather than one shows whether a layer is uniform or carries the
     * patches the profile is meant to produce.
     */
    /**
     * Reads the dimension's own biome field - no extension mapping applied - over a coarse grid, and
     * reports which biomes appear at which heights. That is how a modded biome's own region can be
     * found: whatever the band selects at a given height is what the deep world has to be able to
     * reach, because the deep world is sampled at band heights on purpose.
     *
     * @param centre   the column to scan outward from
     * @param chunks   half-width of the scan, in chunks
     * @param sliceYs  block Y values to probe
     */
    public void debugScanBiomeField(ChunkPos centre, int chunks, int[] sliceYs) {
        java.util.Map<String, int[]> seen = new java.util.TreeMap<>();
        java.util.Map<String, String> where = new java.util.HashMap<>();
        // The pool is what vanilla's picker is allowed to answer with. Modded biomes can only
        // appear below the band if they are in it, because the extension asks this same source.
        java.util.List<String> pool = new java.util.ArrayList<>();
        for (net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> holder
                : generator.biomeSourceForDiagnostics().possibleBiomes()) {
            String id = holder.unwrapKey().map(k -> k.identifier().toString()).orElse("[unregistered]");
            if (!id.startsWith("minecraft:")) {
                pool.add(id);
            }
        }
        java.util.Collections.sort(pool);
        CubicChunks.LOGGER.info("[CubicChunks]   the dimension's biome pool adds {} non-vanilla biomes: {}",
                pool.size(), pool);
        int columns = 0;
        for (int dx = 0; dx <= chunks * 32; dx += 32) {
            for (int dz = 0; dz <= chunks * 32; dz += 32) {
                columns++;
                int bx = centre.getMinBlockX() + dx;
                int bz = centre.getMinBlockZ() + dz;
                for (int s = 0; s < sliceYs.length; s++) {
                    net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome = level.getBiome(
                            new net.minecraft.core.BlockPos(bx, sliceYs[s], bz));
                    String id = biome.unwrapKey().map(k -> k.identifier().toString()).orElse("[unregistered]");
                    int[] tally = seen.computeIfAbsent(id, i -> new int[sliceYs.length]);
                    tally[s]++;
                    if (where.get(id) == null) {
                        where.put(id, bx + "," + sliceYs[s] + "," + bz);
                    }
                }
            }
        }
        // The same question on the extension's side: what does the mapping resolve to at real deep
        // heights, through the code path cube generation uses. A modded biome that vanilla selects
        // inside the band has to show up here too, or the fix is not working.
        net.minecraft.world.level.biome.BiomeSource source = generator.biomeSourceForDiagnostics();
        net.minecraft.world.level.biome.Climate.Sampler sampler = generator.samplerForDiagnostics();
        int[] deepSlices = new int[]{-100, -300, -600, -1000};
        if (source != null && sampler != null) {
            java.util.Map<String, int[]> deep = new java.util.TreeMap<>();
            dev.cubicchunks.worldgen.WorldGenState.REMAP_DEEP_BIOMES.set(true);
            dev.cubicchunks.worldgen.WorldGenState.REMAP_BAND_MIN_Y.set(generator.bandMinY());
            dev.cubicchunks.worldgen.WorldGenState.REMAP_WORLD_MIN_Y.set(level.getMinY());
            dev.cubicchunks.worldgen.WorldGenState.REMAP_BAND_HEIGHT.set(
                    generator.bandMaxY() - generator.bandMinY());
            try {
                for (int dx = 0; dx <= chunks * 32; dx += 32) {
                    for (int dz = 0; dz <= chunks * 32; dz += 32) {
                        int bx = centre.getMinBlockX() + dx;
                        int bz = centre.getMinBlockZ() + dz;
                        for (int s = 0; s < deepSlices.length; s++) {
                            net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome =
                                    source.getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(bx),
                                            net.minecraft.core.QuartPos.fromBlock(deepSlices[s]),
                                            net.minecraft.core.QuartPos.fromBlock(bz), sampler);
                            String id = biome.unwrapKey().map(k -> k.identifier().toString())
                                    .orElse("[unregistered]");
                            deep.computeIfAbsent(id, i -> new int[deepSlices.length])[s]++;
                        }
                    }
                }
            } finally {
                dev.cubicchunks.worldgen.WorldGenState.REMAP_DEEP_BIOMES.set(false);
            }
            CubicChunks.LOGGER.info("[CubicChunks] extension-mapped biomes below the band at y {}: {}"
                    .replace("{}", "{}"), java.util.Arrays.toString(deepSlices), deep.size());
            for (java.util.Map.Entry<String, int[]> entry : deep.entrySet()) {
                StringBuilder line = new StringBuilder();
                for (int s = 0; s < deepSlices.length; s++) {
                    if (entry.getValue()[s] > 0) {
                        line.append(" y=").append(deepSlices[s]).append(':').append(entry.getValue()[s]);
                    }
                }
                CubicChunks.LOGGER.info("[CubicChunks]   {}{} at {}",
                        entry.getKey().startsWith("minecraft:") ? "" : "MODDED ", entry.getKey(), line);
            }
        }

        CubicChunks.LOGGER.info("[CubicChunks] biome field scan over {} columns at y {}: {}", columns,
                java.util.Arrays.toString(sliceYs), sliceYs.length);
        java.util.List<String> ids = new java.util.ArrayList<>(seen.keySet());
        ids.sort((a, b) -> Integer.compare(sum(seen.get(b)), sum(seen.get(a))));
        int shown = 0;
        for (String id : ids) {
            boolean modded = !id.startsWith("minecraft:");
            if (!modded && shown >= 8) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (int s = 0; s < sliceYs.length; s++) {
                if (seen.get(id)[s] > 0) {
                    line.append(" y=").append(sliceYs[s]).append(':').append(seen.get(id)[s]);
                }
            }
            CubicChunks.LOGGER.info("[CubicChunks]   {}{} at {} first seen{}", modded ? "MODDED " : "", id,
                    line, where.get(id));
            shown++;
        }
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }

    /**
     * Counts what structure machinery is actually available on the tested columns: which starts are
     * recorded, how many pieces they carry, how many of those pieces lie below the band, and the
     * height range the pieces cover. This separates "no deep starts exist" from "deep starts exist
     * but nothing is placed from them".
     */
    public void debugReportStructures(java.util.Collection<ChunkPos> positions) {
        java.util.Map<String, int[]> tally = new java.util.TreeMap<>();
        int columns = 0;
        int withStarts = 0;
        int starts = 0;
        for (ChunkPos pos : positions) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk == null) {
                continue;
            }
            columns++;
            if (!chunk.getAllStarts().isEmpty()) {
                withStarts++;
            }
            for (StructureStart start : chunk.getAllStarts().values()) {
                if (start == null || !start.isValid()) {
                    continue;
                }
                starts++;
                for (StructurePiece piece : start.getPieces()) {
                    // Piece class names, because 26.2 keys a chunk's starts by the Structure object
                    // itself rather than a holder, so there is no identifier to read from it here.
                    // Which kinds of piece exist, and how deep they reach, is what this diagnostic is
                    // after.
                    String id = DeepStructures.structureKey(start, level.registryAccess());
                    int[] row = tally.computeIfAbsent(id, i ->
                            new int[] {0, 0, 0, Integer.MAX_VALUE, Integer.MIN_VALUE});
                    row[0]++;
                    row[1]++;
                    BoundingBox box = piece.getBoundingBox();
                    row[3] = Math.min(row[3], box.minY());
                    row[4] = Math.max(row[4], box.maxY());
                    if (box.maxY() < generator.bandMinY()) {
                        row[2]++;
                    }
                }
            }
        }
        CubicChunks.LOGGER.info("[CubicChunks] structures: {} columns, {} carry starts, {} valid starts,"
                + " {} piece kinds | vanilla structure stage ran {} times, deep starts created {},"
                + " deep pieces copied {}", columns, withStarts, starts, tally.size(),
                DeepStructures.STRUCTURE_STAGE_CALLS.get(), DeepStructures.DEEP_STARTS_CREATED.get(),
                DeepStructures.DEEP_PIECES_COPIED.get());
        CubicChunks.LOGGER.info("[CubicChunks]   structures generated here: {} pieces, box y range {}..{}",
                DeepStructures.GEN_PIECE_COUNT, DeepStructures.GEN_MIN_Y, DeepStructures.GEN_MAX_Y);
        CubicChunks.LOGGER.info("[CubicChunks]   transplants: {} source lookups, {} not loaded,"
                + " {} requested, {} loaded, {} pieces placed", DeepStructures.TRANSPLANT_SOURCE_LOOKUPS.get(),
                DeepStructures.TRANSPLANT_SOURCES_UNLOADED.get(),
                DeepStructures.TRANSPLANT_SOURCES_REQUESTED.get(),
                DeepStructures.TRANSPLANT_SOURCES_LOADED.get(),
                DeepStructures.TRANSPLANT_PIECES_PLACED.get());
        CubicChunks.LOGGER.info("[CubicChunks]   light: {}", dev.cubicchunks.core.LightProbe.summary());
        long bearded = DeepStructures.BEARDED_CUBES.get();
        long sampled = DeepStructures.BEARD_SAMPLE_COUNT.get();
        CubicChunks.LOGGER.info("[CubicChunks]   terrain density raised around structures in {} cubes"
                + " ({} pieces); air left by generation in {} sampled bearded cubes: {} per 1k",
                bearded, DeepStructures.BEARD_RIGIDS.get(), sampled,
                sampled == 0L ? 0L : DeepStructures.BEARD_SAMPLE_AIR.get() * 1000L / (4096L * sampled));
        if (!DeepStructures.TRANSPLANTED_BY_STRUCTURE.isEmpty()) {
            java.util.List<String> names = new java.util.ArrayList<>(
                    DeepStructures.TRANSPLANTED_BY_STRUCTURE.keySet());
            names.sort((a, b) -> Long.compare(
                    DeepStructures.TRANSPLANTED_BY_STRUCTURE.get(b).longValue(),
                    DeepStructures.TRANSPLANTED_BY_STRUCTURE.get(a).longValue()));
            StringBuilder line = new StringBuilder();
            for (String name : names.subList(0, Math.min(12, names.size()))) {
                line.append(' ').append(name).append('=')
                        .append(DeepStructures.TRANSPLANTED_BY_STRUCTURE.get(name).longValue());
            }
            CubicChunks.LOGGER.info("[CubicChunks]   transplanted by structure:{}", line);
        }
        for (java.util.Map.Entry<String, int[]> entry : tally.entrySet()) {
            int[] row = entry.getValue();
            CubicChunks.LOGGER.info("[CubicChunks]   {} : {} pieces, {} below the band, {} starts,"
                    + " box y {}..{}", entry.getKey(), row[1], row[2], row[0],
                    row[3] == Integer.MAX_VALUE ? "-" : row[3],
                    row[4] == Integer.MIN_VALUE ? "-" : row[4]);
        }
    }

    public void debugReportBiomeProfile(java.util.Collection<ChunkPos> positions) {
        int stratumHeight = Math.max(16, generator.stratumHeight());
        // Key -2 is the band's open-air slice, -1 its underground slice (y < 0), 0.. are the
        // extension's strata. Comparing -1 with 0 is the biome seam test: the block just below the
        // band should belong to the same kind of biome as the block just above it.
        java.util.Map<Integer, java.util.Map<String, Integer>> buckets = new java.util.TreeMap<>();
        // Section biomes are stored at 4-block resolution: the container is indexed 0..3 per axis.
        int[][] probes = {{0, 0, 0}, {3, 1, 0}, {0, 3, 3}, {3, 3, 1}};
        for (ChunkPos pos : positions) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk == null) {
                continue;
            }
            for (int sy = net.minecraft.core.SectionPos.blockToSectionCoord(generator.bandMaxY()) - 1;
                    sy >= state.minSection(); sy--) {
                long key = SectionPos.asLong(pos.x(), sy, pos.z());
                boolean inBand = sy >= generator.bandMinSection();
                if (!inBand && !generated.contains(key)) {
                    continue;
                }
                int idx = chunk.getSectionIndexFromSectionY(sy);
                if (idx < 0 || idx >= chunk.getSections().length) {
                    continue;
                }
                int bucket = inBand
                        ? (SectionPos.sectionToBlockCoord(sy) < 0 ? -1 : -2)
                        : stratumOf(sy);
                java.util.Map<String, Integer> tally =
                        buckets.computeIfAbsent(bucket, b -> new java.util.HashMap<String, Integer>());
                for (int[] probe : probes) {
                    Holder<Biome> biome = chunk.getSections()[idx].getBiomes().get(probe[0], probe[1], probe[2]);
                    String id = biome.unwrapKey().map(k -> k.identifier().toString()).orElse("[unregistered]");
                    tally.merge(id, 1, Integer::sum);
                }
            }
        }
        for (java.util.Map.Entry<Integer, java.util.Map<String, Integer>> entry : buckets.entrySet()) {
            java.util.Map<String, Integer> tally = entry.getValue();
            if (tally.isEmpty()) {
                continue;
            }
            int key = entry.getKey();
            int topY = key == -2 ? generator.bandMaxY()
                    : (key == -1 ? 0 : generator.bandMinY() - key * stratumHeight);
            int bottomY = key == -2 ? 0
                    : (key == -1 ? generator.bandMinY() : topY - stratumHeight);
            String top = tally.entrySet().stream()
                    .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(5)
                    .map(e -> e.getValue() + "x " + e.getKey())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("-");
            CubicChunks.LOGGER.info("[CubicChunks] biomes y {}..{}: {}", bottomY, topY, top);
        }
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
        // Structure source columns are loaded here, from the tick loop, never from inside chunk
        // generation: asking for a column while one is being generated makes the chunk executor wait
        // on the main thread, which is what put a 60-second tick on the server and had the watchdog
        // kill it. Two per tick keeps a stalled request from dominating a tick.
        loadRequestedStructureSources();
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

        flushColumnWork(false);
    }

    /** Everything outstanding must reach the world before a level is unloaded. */
    public void flushForShutdown() {
        flushColumnWork(true);
    }

    /** Desired below-band cubes, nearest-first around each player's eye level. */
    private void buildWorkQueue() {
        workQueue.clear();
        int vert = CubicConfig.VERTICAL_LOAD_DISTANCE.get();

        for (ServerPlayer player : level.getPlayers(p -> true)) {
            ChunkTrackingView view = player.getChunkTrackingView();
            if (view == ChunkTrackingView.EMPTY) {
                continue;
            }
            List<ChunkPos> columns = new ArrayList<>();
            view.forEach(columns::add);
            collectDesiredCubes(
                    player.chunkPosition(),
                    SectionPos.blockToSectionCoord(Mth.floor(player.getY())),
                    vert, columns, workQueue);
        }
    }

    /**
     * The cubes one streaming centre needs: a window below its eye (with a margin, so digging and
     * falling never outrun generation) and a smaller one above, over the given columns, nearest
     * first so content materializes around the player rather than at the fringes.
     */
    private void collectDesiredCubes(
            ChunkPos centre, int eyeSection, int vert, List<ChunkPos> columns,
            java.util.ArrayDeque<Long> out) {
        int zoneTop = generator.bandMinSection() - 1;
        // Below the player we stream deeper than above: digging/falling must never outrun
        // generation, and pre-generating a margin below makes deep descents seamless.
        int belowDist = vert + FALL_MARGIN_CUBES;
        int yMax = Math.min(eyeSection + vert, zoneTop);
        int yMin = Math.max(eyeSection - belowDist, state.minSection());
        if (yMin > yMax) {
            return;
        }
        final int yMinF = yMin;
        final int yMaxF = yMax;
        final int eyeF = eyeSection;
        final int belowDistF = belowDist;
        final int vertF = vert;
        // Nearest columns first so cubes materialize around the player, not at the fringes.
        columns.sort((a, b) -> Double.compare(distanceSq(centre, a), distanceSq(centre, b)));
        int near = CubicConfig.STREAM_NEAR_RADIUS.get();
        int far = CubicConfig.STREAM_FAR_CUBES.get();
        for (ChunkPos colPos : columns) {
            int away = Math.max(Math.abs(colPos.x() - centre.x()), Math.abs(colPos.z() - centre.z()));
            // Only the columns a player is digging in get a deep vertical window. Out at the edge of
            // the view, the layer the player is standing in is all that can be seen - and each cube
            // there would cost another whole-column save and packet, both of which grow with depth.
            int below = away <= near ? belowDistF : far;
            int above = away <= near ? vertF : far;
            // Ordered as contiguous vertical runs - first down from the eye, then up. A run of
            // neighbouring cubes in one column is filled with a single noise graph (consumeRun),
            // which is what makes building that graph once per column instead of once per cube.
            for (int dy = 0; dy <= below; dy++) {
                int lower = eyeF - dy;
                if (lower >= yMinF && lower <= yMaxF) {
                    out.addLast(SectionPos.asLong(colPos.x(), lower, colPos.z()));
                }
            }
            for (int dy = 1; dy <= above; dy++) {
                int higher = eyeF + dy;
                if (higher >= yMinF && higher <= yMaxF) {
                    out.addLast(SectionPos.asLong(colPos.x(), higher, colPos.z()));
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

    /*
     * Tried and rejected, measured: filling a run of neighbouring cubes of one column with a single
     * NoiseChunk (ExtensionGenerator#generateWindow, still used by generateCube with count 1) cut
     * generation from 3.3 ms to 2.0-2.1 ms per cube - building the noise graph costs 1.7 ms of the
     * per-cube cost, and amortizing it over a run is where that time goes. It was reverted because
     * a 224-block settings window does not produce the same density as a 16-block one: verification
     * came back with whole sections of air below the band, which is exactly the failure mode the
     * extension must not have. Revisiting this needs a window that reproduces the per-cube density
     * result, not simply a taller one.
     */

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
    private void generateAndApply(
            LevelChunk chunk, int idx, int sectionY, LevelChunkSection previous) {
        generateAndApply(chunk, idx, sectionY, previous, null, 0L);
    }

    /**
     * Installs a cube into its column and runs the passes that belong to a cube: the surgical merge
     * over any terrain already there, the feature passes, structure pieces, lighting and the
     * persisted marker.
     *
     * @param pregenerated a cube built by a batched {@code generateWindow} call, or null to build
     *     this cube's noise graph on its own
     * @param noiseNanos  the share of that batch's construction attributed to this cube, for the
     *     cost profile only
     */
    private void generateAndApply(
            LevelChunk chunk, int idx, int sectionY, LevelChunkSection previous,
            LevelChunkSection pregenerated, long noiseNanos) {
        final int stratum = this.profileCost ? stratumOf(sectionY) : -1;
        this.profileStratum = stratum;
        this.generator.setProfileStratum(stratum);
        long phaseStart = System.nanoTime();
        java.util.List<net.minecraft.world.level.levelgen.Beardifier.Rigid> structureRigids = structureRigidsFor(chunk, sectionY);
        LevelChunkSection section = pregenerated != null
                ? pregenerated
                : generator.generateCube(chunk, sectionY, structureRigids);
        // Counted here, before the merge, features or piece placement touch the section: this is the
        // air that terrain generation left in a cube whose density was raised around a structure.
        // Compare it with the same stratum's average from the block census and the effect of the
        // beard is visible or it is not.
        if (!structureRigids.isEmpty()) {
            DeepStructures.BEARDED_CUBES.incrementAndGet();
            DeepStructures.BEARD_RIGIDS.addAndGet(structureRigids.size());
            if (DeepStructures.BEARD_SAMPLE_COUNT.get() < 40L && section != null) {
                DeepStructures.BEARD_SAMPLE_COUNT.incrementAndGet();
                final int[] airCount = new int[1];
                section.getStates().count((state, count) -> {
                    if (state.isAir()) {
                        airCount[0] += count;
                    }
                });
                DeepStructures.BEARD_SAMPLE_AIR.addAndGet(airCount[0]);
            }
        }
        if (pregenerated != null) {
            addPhase(PHASE_NOISE, stratum, noiseNanos);
        } else {
            markPhase(PHASE_NOISE, stratum, phaseStart);
        }

        phaseStart = System.nanoTime();
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
        noteColumnChanged(chunk.getPos());
        markPhase(PHASE_MERGE, stratum, phaseStart);

        // Features always run: the cube's own (deep, remapped) biome drives its feature set.
        phaseStart = System.nanoTime();
        Holder<Biome> cubeBiome = section.getBiomes().get(1, 1, 1);
        placeCubeFeatures(chunk, section, sectionY, cubeBiome);
        markPhase(PHASE_FEATURES, stratum, phaseStart);

        // Deep structure pieces (registered by DeepStructures) re-place themselves into the fresh
        // section, including their interior air - terrain regen alone would fill rooms solid.
        phaseStart = System.nanoTime();
        placeStructurePieces(chunk, sectionY);
        markPhase(PHASE_STRUCTURES, stratum, phaseStart);

        phaseStart = System.nanoTime();
        SectionPos sectionPos = SectionPos.of(chunk.getPos(), sectionY);
        // Notify the light engine that a section changed. The engine keeps its own storage, keyed by
        // section, and sizes it from the level's height range as it was when the engine was built.
        // A section outside that range has no storage entry, and vanilla's light propagation
        // dereferences the layer without checking (LayerLightSectionStorage#getStoredLevel), which
        // faults on a light worker thread and takes the server down at random during startup. Only
        // sections the engine actually tracks are announced here; the rest are lit when the chunk is
        // sent, which builds the packet's light from the same engine.
        int minSection = net.minecraft.core.SectionPos.blockToSectionCoord(level.getMinY());
        int maxSection = net.minecraft.core.SectionPos.blockToSectionCoord(level.getMinY() + level.getHeight());
        if (sectionY >= minSection && sectionY < maxSection) {
            level.getChunkSource().getLightEngine().updateSectionStatus(sectionPos, section.hasOnlyAir());
        }

        persistGeneratedMarker(chunk.getPos(), chunk);
        markPhase(PHASE_TAIL, stratum, phaseStart);
        if (stratum >= 0) {
            this.profileCubes[stratum]++;
        }
        this.profileStratum = -1;
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
    /**
     * The terrain adaptations vanilla would apply around the structures this cube is about to
     * receive. Computed before the cube's noise is sampled, which is the whole point: vanilla raises
     * density around a structure's bounding box so caves do not cut through it, and that only works
     * while terrain is being generated. Doing it afterwards - which is what placing pieces does -
     * leaves a mineshaft standing inside a cave that was carved as though the shaft were not there,
     * with its floor missing and its supports hanging in void.
     */
    private java.util.List<net.minecraft.world.level.levelgen.Beardifier.Rigid> structureRigidsFor(LevelChunk chunk, int sectionY) {
        java.util.List<StructurePiece> candidates = new java.util.ArrayList<>();
        java.util.List<StructurePiece> own = pieceIndexForColumn(chunk).get(sectionY);
        if (own != null) {
            candidates.addAll(own);
        }
        ChunkPos chunkPos = chunk.getPos();
        int cubeMinY = sectionY << 4;
        BoundingBox cubeBox = new BoundingBox(
                chunkPos.getMinBlockX(), cubeMinY, chunkPos.getMinBlockZ(),
                chunkPos.getMinBlockX() + 15, cubeMinY + 15, chunkPos.getMinBlockZ() + 15);
        candidates.addAll(transplantedPieces(chunkPos, stratumOf(sectionY), cubeBox));
        if (candidates.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<net.minecraft.world.level.levelgen.Beardifier.Rigid> rigids = new java.util.ArrayList<>();
        for (StructurePiece piece : candidates) {
            net.minecraft.world.level.levelgen.structure.TerrainAdjustment adaptation = this.pieceAdaptations.get(piece);
            if (adaptation == null || adaptation == net.minecraft.world.level.levelgen.structure.TerrainAdjustment.NONE) {
                continue; // vanilla leaves these alone too
            }
            rigids.add(new net.minecraft.world.level.levelgen.Beardifier.Rigid(piece.getBoundingBox(), adaptation, 0));
        }
        return rigids;
    }

    private void placeStructurePieces(LevelChunk chunk, int sectionY) {
        if (!CubicConfig.DEEP_STRUCTURES.get()) {
            return;
        }
        Map<Integer, java.util.List<StructurePiece>> index = pieceIndexForColumn(chunk);
        java.util.List<StructurePiece> own = index.get(sectionY);
        java.util.List<StructurePiece> pieces = new java.util.ArrayList<>();
        if (own != null) {
            pieces.addAll(own);
        }
        ChunkPos chunkPos = chunk.getPos();
        int cubeMinY = sectionY << 4;
        BoundingBox cubeBox = new BoundingBox(
                chunkPos.getMinBlockX(), cubeMinY, chunkPos.getMinBlockZ(),
                chunkPos.getMinBlockX() + 15, cubeMinY + 15, chunkPos.getMinBlockZ() + 15);
        // Structures of this stratum, taken from the band column this slice maps to.
        pieces.addAll(transplantedPieces(chunkPos, stratumOf(sectionY), cubeBox));
        if (pieces.isEmpty()) {
            return;
        }
        var structureManager = level.structureManager();
        for (StructurePiece piece : pieces) {
            if (!piece.getBoundingBox().intersects(cubeBox)) {
                continue;
            }
            RandomSource rand = RandomSource.create(level.getSeed()
                    ^ sectionY * 1000003587L ^ chunkPos.pack()
                    ^ System.identityHashCode(piece));
            DeepStructures.TRANSPLANT_PIECES_PLACED.incrementAndGet();
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
    /** Transplanted pieces per (source column, stratum): the same source is reused while a player
     * keeps streaming nearby columns, so the cost is paid once per source, not once per cube. */
    /** Set true only by the measurement run that proves a transplanted piece can reach a cube. */
    private static final boolean TEMPORARY_ALLOW_SURFACE = false;

    /**
     * The terrain adaptation each known piece's structure asks for (beard thin, beard box,
     * encapsulate). Vanilla reads this from the start while building a beardifier; a piece held on
     * its own cannot answer that, so it is recorded at the moment the piece is taken from a start.
     */
    /**
     * Source columns a transplant lookup wanted but could not read. Queued by the lookup, drained by
     * the tick loop. Kept as pending until it succeeds, and failures are deliberately not cached:
     * caching an empty result made a column that happened to be unloaded stay structureless for the
     * rest of the session.
     */
    private final java.util.ArrayDeque<ChunkPos> pendingStructureSources = new java.util.ArrayDeque<>();
    private final java.util.Set<ChunkPos> alreadyRequestedSources = new java.util.HashSet<>();
    private static final int MAX_PENDING_STRUCTURE_SOURCES = 512;
    private static final int STRUCTURE_SOURCE_LOADS_PER_TICK = 2;

    private void requestStructureSource(ChunkPos source) {
        if (this.pendingStructureSources.size() >= MAX_PENDING_STRUCTURE_SOURCES
                || !this.alreadyRequestedSources.add(source)) {
            return;
        }
        this.pendingStructureSources.add(source);
        DeepStructures.TRANSPLANT_SOURCES_REQUESTED.incrementAndGet();
    }

    private void loadRequestedStructureSources() {
        if (this.pendingStructureSources.isEmpty()) {
            return;
        }
        for (int i = 0; i < STRUCTURE_SOURCE_LOADS_PER_TICK; i++) {
            ChunkPos source = this.pendingStructureSources.poll();
            if (source == null) {
                return;
            }
            if (level.getChunkSource().getChunkNow(source.x(), source.z()) == null) {
                level.getChunk(source.x(), source.z());
            }
            if (level.getChunkSource().getChunkNow(source.x(), source.z()) != null) {
                DeepStructures.TRANSPLANT_SOURCES_LOADED.incrementAndGet();
            } else {
                // not ready yet: ask again on a later tick rather than dropping the request
                this.alreadyRequestedSources.remove(source);
            }
        }
    }

    private final java.util.Map<StructurePiece, net.minecraft.world.level.levelgen.structure.TerrainAdjustment> pieceAdaptations =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<java.util.List<StructurePiece>> transplantCache =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();


    private java.util.List<StructurePiece> transplantedPieces(ChunkPos target, int stratum, BoundingBox cubeBox) {
        if (stratum < 1 || !CubicConfig.DEEP_STRUCTURES.get() || !CubicConfig.TRANSPLANT_STRUCTURES.get()) {
            return java.util.Collections.emptyList();
        }
        StructurePieceSerializationContext context = DeepStructures.SHARED_CONTEXT;
        if (context == null) {
            return java.util.Collections.emptyList(); // no serialization context seen yet
        }
        ChunkPos source = DeepStructures.sourceColumnFor(target, stratum, level.getSeed());
        // The per-column index already covers this column and its neighbours' starts, including the
        // deep copies the generation stage made from them. Reading those again would place twice.
        if (Math.abs(source.x() - target.x()) <= 1 && Math.abs(source.z() - target.z()) <= 1) {
            return java.util.Collections.emptyList();
        }
        long key = source.pack() ^ ((long) stratum * 0x9E3779B97F4A7C15L);
        java.util.List<StructurePiece> cached = this.transplantCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (this.transplantCache.size() > 4096) {
            this.transplantCache.clear();
        }
        DeepStructures.TRANSPLANT_SOURCE_LOOKUPS.incrementAndGet();
        // Only the source column itself is loaded. Its neighbours are read when already loaded, so
        // a structure that spans chunk borders may arrive partly, rather than this costing a grid
        // of chunk loads for every cube.
        LevelChunk origin = level.getChunkSource().getChunkNow(source.x(), source.z());
        if (origin == null) {
            DeepStructures.TRANSPLANT_SOURCES_UNLOADED.incrementAndGet();
            requestStructureSource(source);
            return java.util.Collections.emptyList();
        }
        java.util.List<StructurePiece> pieces = new java.util.ArrayList<>();
        int shift = -stratum * Math.max(16, generator.stratumHeight());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                LevelChunk col = dx == 0 && dz == 0
                        ? origin : level.getChunkSource().getChunkNow(source.x() + dx, source.z() + dz);
                if (col == null) {
                    continue;
                }
                for (StructureStart start : col.getAllStarts().values()) {
                    if (start == null || !start.isValid()) {
                        continue;
                    }
                    String structureName = DeepStructures.structureKey(start, level.registryAccess());
                    net.minecraft.world.level.levelgen.structure.TerrainAdjustment adaptation = start.getStructure().terrainAdaptation();
                    for (StructurePiece piece : start.getPieces()) {
                        BoundingBox box = piece.getBoundingBox();
                        // Only structures that reach below the surface. A village is a structure too,
                        // and a village at y=-900 is not what "underground structures" means.
                        if (!TEMPORARY_ALLOW_SURFACE && box.minY() >= 0) {
                            continue;
                        }
                        // The piece came from a different column than this cube's. Because a
                        // stratum's mapping is a pure translation, the distance back to the cube's
                        // column is the same for every cube that asks for this source, so the cached
                        // list stays valid and the piece lands over the column that asked for it.
                        StructurePiece copy = DeepStructures.copyTranslated(
                                piece, new DeepStructures.RegistryAccessHolder(context),
                                (target.x() - (source.x() + dx)) * 16, shift,
                                (target.z() - (source.z() + dz)) * 16);
                        if (copy != null && (cubeBox == null || copy.getBoundingBox().intersects(cubeBox))) {
                            this.pieceAdaptations.put(copy, adaptation);
                            pieces.add(copy);
                            DeepStructures.TRANSPLANTED_BY_STRUCTURE
                                    .computeIfAbsent(structureName,
                                            k -> new java.util.concurrent.atomic.AtomicLong())
                                    .incrementAndGet();
                        }
                    }
                }
            }
        }
        this.transplantCache.put(key, pieces);
        return pieces;
    }

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
                    net.minecraft.world.level.levelgen.structure.TerrainAdjustment adaptation = start.getStructure().terrainAdaptation();
                    for (StructurePiece piece : start.getPieces()) {
                        this.pieceAdaptations.put(piece, adaptation);
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

    /**
     * Feature passes already evaluated for the column currently being worked on, keyed by
     * (generation step, index in that step, tile of the feature's native span).
     *
     * <p>Evaluating a placement chain is the whole cost of a feature pass, and a pass scatters its
     * positions over the feature's full span - so running it again for every 16-block cube of that
     * span means each pass is evaluated a score of times to keep the handful of positions that fall
     * in each cube. Cubes of one column overlap the same tiles, so a pass is evaluated once and its
     * positions are packed (x/z as offsets from the column corner, Y relative to the tile) for the
     * cubes to read. Positions are a function of (column, feature, tile) alone, and each placement
     * gets its own seeded random, so a cube's contents never depend on which cube came first.
     */
    private final Map<Long, long[]> featureTilePasses = new HashMap<>();
    private long featureCacheColumn = Long.MIN_VALUE;
    /** Debug: a stand-in viewer for benchmarks, so the watched/near rules behave as they do in play. */
    private ChunkPos benchViewer;
    private net.minecraft.resources.Identifier featureCacheBiome = null;
    private long profileFeatureEvals;
    private boolean warnedFeaturePassTooWide;

    /** Debug: per-feature tallies of what the deep-world placement pipeline actually did. */
    private final Map<String, long[]> featureStats = new java.util.LinkedHashMap<>();
    /** Per feature: measured reach (vmin, span), tiles seen, count multiplier seen, attempts/cube. */
    private final Map<String, long[]> featureReach = new java.util.LinkedHashMap<>();
    private final Map<String, String> featureNotes = new java.util.LinkedHashMap<>();

    private void recordFeatureStat(
            Holder<PlacedFeature> holder, long positions, long placements) {
        if (!this.profileCost) {
            return;
        }
        String id = featureId(holder);
        long[] stats = this.featureStats.computeIfAbsent(id, k -> new long[3]);
        stats[0] += positions;
        stats[1] += placements;
    }

    private void recordFeatureReach(Holder<PlacedFeature> holder, int vmin, int span, int tiles, double multiplier) {
        if (!this.profileCost) {
            return;
        }
        long[] r = this.featureReach.computeIfAbsent(featureId(holder), k -> new long[4]);
        r[0] = vmin;
        r[1] = span;
        r[2] = Math.max(r[2], tiles);
        r[3] = (long) (multiplier * 1000.0);
    }

    private void recordFeatureNote(Holder<PlacedFeature> holder, String note) {
        if (!this.profileCost) {
            return;
        }
        String id = featureId(holder);
        this.featureStats.computeIfAbsent(id, k -> new long[3]);
        if (!"ran".equals(this.featureNotes.get(id))) {
            this.featureNotes.put(id, note);
        }
    }

    private void recordFeatureSkipped(Holder<PlacedFeature> holder, String reason) {
        if (!this.profileCost) {
            return;
        }
        String id = featureId(holder);
        long[] stats = this.featureStats.computeIfAbsent(id, k -> new long[3]);
        stats[2]++;
        if (!"ran".equals(this.featureNotes.get(id))) {
            this.featureNotes.putIfAbsent(id, reason);
        }
    }

    private static String featureId(Holder<PlacedFeature> holder) {
        return holder.unwrapKey().map(k -> k.identifier().toString()).orElse("[unnamed]");
    }

    public void debugReportFeatureStats() {
        CubicChunks.LOGGER.info("[CubicChunks] --- placement below the band, per feature"
                + " (positions produced / placements attempted)");
        this.featureStats.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, long[]>comparingByValue(
                        (a, b) -> Long.compare(a[0] + a[1], b[0] + b[1])).reversed())
                .forEach(e -> CubicChunks.LOGGER.info("[CubicChunks]   {} : positions {}, placements {}",
                        e.getKey(), e.getValue()[0], e.getValue()[1]));
        this.featureStats.forEach((id, stats) -> {
            long[] r = this.featureReach.get(id);
            if (r != null) {
                CubicChunks.LOGGER.info(
                        "[CubicChunks]   {} : reach y {}..{} (span {}), tiles per cube {}, count multiplier {}",
                        id, r[0], r[0] + r[1], r[1], r[2], r[3] / 1000.0);
            }
        });
        this.featureStats.entrySet().stream()
                .filter(e -> e.getValue()[1] == 0L)
                .forEach(e -> CubicChunks.LOGGER.info("[CubicChunks]   {} : {} (positions read {}, considered by {} cubes)",
                        e.getKey(), this.featureNotes.getOrDefault(e.getKey(), "?"),
                        e.getValue()[0], e.getValue()[2]));
    }

    /**
     * Debug: which block types actually appear at which depth in generated cubes, read straight from
     * the section palettes. Answers "the rare ones don't show up" with a count instead of a guess.
     */
    public void debugReportBlockCensus(java.util.Collection<ChunkPos> positions) {
        int stratumHeight = Math.max(16, generator.stratumHeight());
        java.util.Map<Integer, java.util.Map<String, Long>> census = new java.util.TreeMap<>();
        java.util.Map<Integer, Long> scanned = new java.util.TreeMap<>();
        java.util.Map<Integer, Long> air = new java.util.TreeMap<>();
        for (ChunkPos pos : positions) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk == null) {
                continue;
            }
            for (int sy = net.minecraft.core.SectionPos.blockToSectionCoord(generator.bandMaxY()) - 1;
                    sy >= state.minSection(); sy--) {
                boolean inBand = sy >= generator.bandMinSection();
                if (!inBand && !generated.contains(SectionPos.asLong(pos.x(), sy, pos.z()))) {
                    continue;
                }
                int idx = chunk.getSectionIndexFromSectionY(sy);
                if (idx < 0 || idx >= chunk.getSections().length) {
                    continue;
                }
                // -1 is vanilla's deepslate layer (the part of the band that is under stone),
                // -2 the rest of the band. Comparing the deepslate layer with the first stratum
                // below the band is the seam test: the caves there must be the same caves.
                int bucket = inBand ? (sy < 0 ? -1 : -2) : stratumOf(sy);
                java.util.Map<String, Long> tally =
                        census.computeIfAbsent(bucket, s -> new java.util.HashMap<>());
                scanned.merge(bucket, 4096L, Long::sum);
                final int sectionY = sy;
                chunk.getSections()[idx].getStates().count((state, count) -> {
                    net.minecraft.resources.Identifier id = net.minecraft.core.registries
                            .BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    String name = id == null ? "?" : id.getPath();
                    if (name.equals("air")) {
                        int airBucket = sectionY >= generator.bandMinSection()
                                ? (sectionY < 0 ? -1 : -2)
                                : stratumOf(sectionY);
                        air.merge(airBucket, (long) count, Long::sum);
                        return;
                    }
                    if (name.equals("deepslate") || name.equals("stone") || name.equals("bedrock")) {
                        return;
                    }
                    tally.merge(name, (long) count, Long::sum);
                });
            }
        }
        for (java.util.Map.Entry<Integer, java.util.Map<String, Long>> stratum : census.entrySet()) {
            long blocks = Math.max(1L, scanned.getOrDefault(stratum.getKey(), 1L));
            int topY = stratum.getKey() == -1
                    ? 0
                    : (stratum.getKey() == -2
                            ? generator.bandMaxY()
                            : generator.bandMinY() - stratum.getKey() * stratumHeight);
            String top = stratum.getValue().entrySet().stream()
                    .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(14)
                    .map(e -> e.getKey() + " x" + e.getValue()
                            + " (" + String.format(java.util.Locale.ROOT, "%.3f",
                            1000.0 * e.getValue() / (double) blocks) + " per 1k)")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("-");
            CubicChunks.LOGGER.info("[CubicChunks] census y {}..{} over {} blocks: air {} per 1k; {}",
                    topY - stratumHeight, topY, blocks,
                    String.format(java.util.Locale.ROOT, "%.1f",
                            1000.0 * air.getOrDefault(stratum.getKey(), 0L) / (double) blocks), top);
        }
    }

    /** The feature cache belongs to one column and one biome; anything else starts a new one. */
    private void useFeatureCacheFor(ChunkPos chunkPos, Holder<Biome> cubeBiome) {
        net.minecraft.resources.Identifier biome = cubeBiome.unwrapKey()
                .map(k -> k.identifier())
                .orElse(null);
        long column = chunkPos.pack();
        if (column != this.featureCacheColumn
                || !java.util.Objects.equals(biome, this.featureCacheBiome)) {
            this.featureCacheColumn = column;
            this.featureCacheBiome = biome;
            this.featureTilePasses.clear();
        }
    }

    /**
     * One band-pass of a feature over one tile of its native span: the positions it scatters, packed
     * for the cubes of this column to read, or null if a position fell outside what the packing can
     * hold (which vanilla placement chains do not produce in practice).
     */
    /**
     * Runs a feature's placement chain for this cube only, and places what lands inside it. Used for
     * features whose vertical anchoring cannot be resolved without the real world (terrain scans,
     * surface-relative limits): a cached band-pass would silently drop exactly those.
     */
    /**
     * Attempts per cube for the in-cube fallback, before the depth bonus is applied. Each attempt
     * stands in for the share of a band pass that one 16-block cube would have kept, so density
     * stays close to the tiled model rather than being invented from scratch.
     */
    /**
     * Ore and deposit features are not force-placed by the in-cube fallback: their unresolvable
     * variants (vanilla's ore_granite_upper and friends, whose targets are stone, which does not
     * exist below the band) would be placed at a density nothing in the data supports, and their
     * deepslate counterparts already run through the normal path.
     */
    /**
     * The registry name of a configured feature's type, for example "minecraft:geode" or
     * "minecraft:scattered_ore". The class name cannot be used: {@code ConfiguredFeature} holds its
     * feature as a {@code Holder<FeatureType>}, a factory wrapper, so asking it for a class name only
     * ever answers "FeatureType" - which is why an earlier check written that way matched nothing and
     * geodes kept placing no matter what it said.
     */
    private static String featureTypeId(Holder<PlacedFeature> holder) {
        return holder.value().feature().unwrapKey()
                .map(key -> key.identifier().toString().toLowerCase(java.util.Locale.ROOT))
                .orElse("");
    }

    /** Features that replace a whole volume around their position rather than adding a vein. */
    private static boolean isVolumeFeature(Holder<PlacedFeature> holder) {
        return featureTypeId(holder).contains("geode");
    }

    private static boolean isOreOrDiskFeature(Holder<PlacedFeature> holder) {
        String type = featureTypeId(holder);
        return type.contains("ore") || type.contains("disk") || type.contains("spring")
                || type.contains("geode");
    }

    private static final int IN_CUBE_FALLBACK_ATTEMPTS = 2;

    /**
     * Places a feature anchored to a spot chosen inside this cube, skipping the modifiers that only
     * say how often and at which declared height (the cube position stands in for those) and keeping
     * the ones that decide whether it actually fits: surface scans, world checks, offsets and biome.
     */
    private long runFeatureInCube(
            Holder<PlacedFeature> holder, PlacedFeature placed, CubeGenAccess access, ChunkGenerator gen,
            PlacementContext context, ChunkPos chunkPos, long featureSeed,
            int cubeMinY, int cubeMaxY, int stratum) {
        int attempts = (int) Math.max(1L, Math.round(IN_CUBE_FALLBACK_ATTEMPTS
                * (1.0 + stratum * CubicConfig.ORE_DEPTH_BONUS.get())));
        long placedCount = 0;
        for (int a = 0; a < attempts; a++) {
            RandomSource rnd = RandomSource.create(featureSeed
                    ^ (cubeMinY * 0x9E3779B97F4A7C15L) ^ (a * 0x2545F4914F6CDD1DL));
            BlockPos base = new BlockPos(
                    chunkPos.getMinBlockX() + rnd.nextInt(16),
                    cubeMinY + rnd.nextInt(16),
                    chunkPos.getMinBlockZ() + rnd.nextInt(16));
            Stream<BlockPos> stream = Stream.of(base);
            try {
                for (PlacementModifier modifier : placementChain(placed)) {
                    if (modifier instanceof HeightRangePlacement
                            || modifier instanceof net.minecraft.world.level.levelgen.placement.InSquarePlacement
                            || modifier instanceof net.minecraft.world.level.levelgen.placement.CountPlacement) {
                        continue;
                    }
                    stream = stream.flatMap(pos -> modifier.getPositions(context, rnd, pos));
                }
                java.util.Iterator<BlockPos> it = stream.iterator();
                for (int n = 0; n < 8 && it.hasNext(); n++) {
                    BlockPos pos = it.next();
                    if (pos.getY() < state.minY() || pos.getY() >= generator.bandMinY()) {
                        continue;
                    }
                    holder.value().feature().value().place(access, gen, rnd, pos);
                    placedCount++;
                }
            } catch (Throwable e) {
                if (complainedFeatureFailures.add(holder.value().feature().value().getClass())) {
                    CubicChunks.LOGGER.warn(
                            "[CubicChunks] in-cube feature placement failed (suppressing repeats): {}",
                            e.toString());
                }
            }
        }
        return placedCount;
    }

    private long runFeatureInPlace(
            Holder<PlacedFeature> holder, PlacedFeature placed, CubeGenAccess access, ChunkGenerator gen,
            PlacementContext context, ChunkPos chunkPos, long featureSeed, double multiplier,
            int cubeMinY, int cubeMaxY) {
        RandomSource rand = RandomSource.create(featureSeed);
        Stream<BlockPos> stream = Stream.of(new BlockPos(
                chunkPos.getMinBlockX(), generator.bandMinY(), chunkPos.getMinBlockZ()));
        java.util.List<BlockPos> inside = new java.util.ArrayList<>();
        WorldGenState.CURRENT_MULTIPLIER.set(multiplier);
        try {
            for (PlacementModifier modifier : placementChain(placed)) {
                stream = stream.flatMap(pos -> modifier.getPositions(context, rand, pos));
            }
            stream.forEach(pos -> {
                int y = pos.getY();
                if (y >= cubeMinY && y < cubeMaxY) {
                    inside.add(pos.immutable());
                }
            });
        } finally {
            WorldGenState.CURRENT_MULTIPLIER.set(1.0);
        }
        long placedCount = 0;
        for (int i = 0; i < inside.size(); i++) {
            try {
                holder.value().feature().value().place(access, gen, RandomSource.create(featureSeed
                        ^ (cubeMinY * 0x9E3779B97F4A7C15L) ^ (i * 0x2545F4914F6CDD1DL)), inside.get(i));
                placedCount++;
            } catch (Throwable e) {
                if (complainedFeatureFailures.add(holder.value().feature().value().getClass())) {
                    CubicChunks.LOGGER.warn(
                            "[CubicChunks] deep feature placement failed (suppressing repeats): {}",
                            e.toString());
                }
            }
        }
        return placedCount;
    }

    private long[] evaluateFeatureTile(
            Holder<PlacedFeature> holder, PlacedFeature placed, CubeGenAccess access, ChunkGenerator gen,
            PlacementContext context, ChunkPos chunkPos, int tile, int tileBase, int span,
            double multiplier, long featureSeed) {
        RandomSource rand = RandomSource.create(featureSeed ^ (tile * 0x9E3779B97F4A7C15L));
        Stream<BlockPos> stream = Stream.of(new BlockPos(
                chunkPos.getMinBlockX(), generator.bandMinY(), chunkPos.getMinBlockZ()));
        java.util.ArrayList<Long> out = new java.util.ArrayList<>();
        WorldGenState.CURRENT_MULTIPLIER.set(multiplier);
        try {
            for (PlacementModifier modifier : placementChain(placed)) {
                stream = stream.flatMap(pos -> modifier.getPositions(context, rand, pos));
            }
            stream.forEach(pos -> {
                int y = pos.getY() - tile * span;
                if (y < state.minY() + FEATURE_FLOOR_MARGIN || y >= generator.bandMinY()) {
                    return;
                }
                int relY = y - tileBase;
                int xOff = pos.getX() - chunkPos.getMinBlockX();
                int zOff = pos.getZ() - chunkPos.getMinBlockZ();
                if (relY < 0 || relY > 0xFFFFFL || xOff < -1024 || xOff > 1023
                        || zOff < -1024 || zOff > 1023) {
                    if (!this.warnedFeaturePassTooWide) {
                        this.warnedFeaturePassTooWide = true;
                        CubicChunks.LOGGER.warn(
                                "[CubicChunks] a position from a feature pass fell outside the tile"
                                        + " index for that feature ({}); the position is dropped, other"
                                        + " positions of the same pass are unaffected",
                                holder.unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
                    }
                    return;
                }
                out.add(((long) relY << 22) | ((zOff & 0x7FFL) << 11) | (xOff & 0x7FFL));
            });
        } catch (Throwable t2) {
            if (complainedFeatureFailures.add(placed.getClass())) {
                CubicChunks.LOGGER.warn(
                        "[CubicChunks] deep feature planning failed (suppressing repeats): {}",
                        t2.toString());
            }
            return null;
        } finally {
            WorldGenState.CURRENT_MULTIPLIER.set(1.0);
        }
        long[] packed = new long[out.size()];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = out.get(i);
        }
        return packed;
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

    /** Extra cubes streamed below each player's eye, so descents never outrun generation. */
    private static final int FALL_MARGIN_CUBES = 4;

    /**
     * How far above the world bottom feature anchors are kept. A feature's own blob extends several
     * blocks around its anchor, and a block written below the bottom of the level lands outside the
     * light engine's section range - which makes its light updates walk into sections the engine
     * has no data for and fails inside them.
     */
    private static final int FEATURE_FLOOR_MARGIN = 16;

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

    private void noteColumnChanged(ChunkPos pos) {
        long column = pos.pack();
        columnLastTouched.put(column, level.getGameTime());
        pendingSave.add(column);
        pendingSend.add(column);
    }

    /**
     * Hand columns back to vanilla once they stop changing: mark them unsaved so the normal save
     * path serialises them once, and refresh tracking clients with one chunk packet per column.
     * Columns a player is standing in (or within {@link #NEAR_COLUMNS} of) go out immediately so
     * nothing the player can fall into is ever withheld; the rest wait for a quiet period.
     */
    private void flushColumnWork(boolean force) {
        if (pendingSave.isEmpty() && pendingSend.isEmpty()) {
            return;
        }
        int now = (int) level.getGameTime();
        List<ServerPlayer> players = level.getPlayers(p -> true);
        var lightEngine = level.getChunkSource().getLightEngine();
        int sendBudget = MAX_RESENDS_PER_TICK;
        for (long column : pendingSave.toLongArray()) {
            ChunkPos cp = ChunkPos.unpack(column);
            LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x(), cp.z());
            if (chunk == null) {
                // unloaded out from under us: hand it to vanilla so nothing is lost
                pendingSave.remove(column);
                pendingSend.remove(column);
                columnLastTouched.remove(column);
                continue;
            }
            long lastTouched = columnLastTouched.getOrDefault(column, now);
            // No player has this column in view any more: it is on its way out of the level, so
            // hand it to the save path now rather than holding it back.
            boolean watched = !trackableView(players, cp).isEmpty() || isBenchViewed(cp);
            boolean due = force || !watched
                    || now - lastTouched >= SAVE_QUIET_TICKS
                    || now - lastTouched > WORK_DEFER_LIMIT_TICKS;
            if (due) {
                chunk.markUnsaved();
                pendingSave.remove(column);
                this.profileSaveMarks++;
            }
        }

        for (long column : pendingSend.toLongArray()) {
            ChunkPos cp = ChunkPos.unpack(column);
            if (!pendingSave.contains(column) && !force && sendBudget <= 0) {
                continue; // keep whole-column refreshes bounded per tick
            }
            long lastTouched = columnLastTouched.getOrDefault(column, now);
            boolean quiet = now - lastTouched >= SEND_QUIET_TICKS;
            if (!force && !quiet && !isNearPlayer(players, cp)) {
                continue; // far columns are refreshed when their generation settles, not per cube
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x(), cp.z());
            if (chunk == null) {
                pendingSend.remove(column);
                continue;
            }
            java.util.List<ServerPlayer> tracking = trackableView(players, cp);
            if (tracking.isEmpty()) {
                pendingSend.remove(column); // nobody can see it; vanilla sends it when they can
                continue;
            }
            sendBudget--;
            pendingSend.remove(column);
            this.profileSends++;
            ClientboundLevelChunkWithLightPacket packet =
                    new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null);
            for (ServerPlayer player : tracking) {
                player.connection.send(packet);
            }
        }
    }

    private boolean isBenchViewed(ChunkPos pos) {
        return benchViewer != null
                && Math.max(Math.abs(benchViewer.x() - pos.x()), Math.abs(benchViewer.z() - pos.z()))
                <= CubicConfig.STREAM_NEAR_RADIUS.get();
    }

    private static boolean isNearPlayer(List<ServerPlayer> players, ChunkPos pos) {
        int near = CubicConfig.STREAM_NEAR_RADIUS.get();
        for (ServerPlayer player : players) {
            ChunkPos centre = player.chunkPosition();
            if (Math.max(Math.abs(centre.x() - pos.x()), Math.abs(centre.z() - pos.z())) <= near) {
                return true;
            }
        }
        return false;
    }

    private static List<ServerPlayer> trackableView(List<ServerPlayer> players, ChunkPos pos) {
        List<ServerPlayer> out = new ArrayList<>(2);
        for (ServerPlayer player : players) {
            if (player.getChunkTrackingView().contains(pos)) {
                out.add(player);
            }
        }
        return out;
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
        if (generated.contains(SectionPos.asLong(pos.getX() >> 4, cubeY, pos.getZ() >> 4))) {
            noteColumnChanged(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
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
        noteColumnChanged(chunk.getPos());
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
    /**
     * The chain used for a feature. Normally vanilla's own. A feature borrowed from another biome's
     * list has its {@code BiomeFilter} removed first, otherwise that filter rejects the feature at
     * the origin - the biome there does not list it - and the feature produces no positions at all,
     * silently. Only pooled features get an overridden chain; everything else is untouched.
     */
    private static final java.util.Map<PlacedFeature, List<PlacementModifier>> CHAIN_OVERRIDES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static List<PlacementModifier> placementChain(PlacedFeature placed) {
        List<PlacementModifier> override = CHAIN_OVERRIDES.get(placed);
        return override != null ? override : placed.placement();
    }

    private static void overrideChainWithoutBiomeFilter(PlacedFeature placed) {
        if (CHAIN_OVERRIDES.containsKey(placed)) {
            return;
        }
        List<PlacementModifier> stripped = new java.util.ArrayList<>();
        for (PlacementModifier modifier : placed.placement()) {
            if (!(modifier instanceof net.minecraft.world.level.levelgen.placement.BiomeFilter)) {
                stripped.add(modifier);
            }
        }
        CHAIN_OVERRIDES.put(placed, List.copyOf(stripped));
    }

    /**
     * Every ore-shaped feature attached to any biome this dimension can actually generate. Built once
     * and cached. Below the band these are offered to every cube, not only to cubes whose biome
     * lists them: a mod that gates an ore behind one biome (Mythic's gems sit in its own cave
     * biomes) otherwise has that ore absent at depth everywhere, because depth biome choice is a
     * different selection from the surface's. Surfaces are unchanged - this only runs below the band.
     */
    private java.util.List<Holder<PlacedFeature>> dimensionOrePool;

    private java.util.List<Holder<PlacedFeature>> oreFeaturesInDimension() {
        if (this.dimensionOrePool != null) {
            return this.dimensionOrePool;
        }
        java.util.LinkedHashMap<String, Holder<PlacedFeature>> byId = new java.util.LinkedHashMap<>();
        for (Holder<Biome> biomeHolder : generator.biomeSourceForDiagnostics().possibleBiomes()) {
            for (net.minecraft.core.HolderSet<PlacedFeature> step
                    : biomeHolder.value().getGenerationSettings().features()) {
                for (Holder<PlacedFeature> holder : step) {
                    if (isOrePoolFeature(holder)) {
                        byId.putIfAbsent(featureId(holder), holder);
                    }
                }
            }
        }
        this.dimensionOrePool = new java.util.ArrayList<>(byId.values());
        CubicChunks.LOGGER.info("[CubicChunks] ore pool offered below the band: {} features",
                this.dimensionOrePool.size());
        return this.dimensionOrePool;
    }

    private static boolean isOrePoolFeature(Holder<PlacedFeature> holder) {
        if (isVolumeFeature(holder)) {
            return false; // geodes are volume replacements and stay where vanilla puts them
        }
        String type = featureTypeId(holder);
        if (type.contains("ore") || type.contains("deposit")) {
            return true;
        }
        // "vein" and "cluster" are also feature types for decorations - sculk_vein, dripstone_cluster -
        // which are not ores and do not belong in an ore pool. Accept those only when the feature is
        // itself named as an ore, which is how mods tend to write custom ore clusters.
        String name = featureId(holder);
        return (type.contains("vein") || type.contains("cluster")) && name.contains("ore");
    }

    /** The feature passes for a cube: its own biome's, then the dimension's ores it does not list. */
    private java.util.List<java.util.List<Holder<PlacedFeature>>> featurePassesFor(Holder<Biome> cubeBiome) {
        java.util.List<java.util.List<Holder<PlacedFeature>>> passes = new java.util.ArrayList<>();
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (net.minecraft.core.HolderSet<PlacedFeature> step
                : cubeBiome.value().getGenerationSettings().features()) {
            java.util.List<Holder<PlacedFeature>> copy = new java.util.ArrayList<>();
            for (Holder<PlacedFeature> holder : step) {
                copy.add(holder);
                listed.add(featureId(holder));
            }
            passes.add(copy);
        }
        if (CubicConfig.ORES_AT_ALL_DEPTHS.get()) {
            java.util.List<Holder<PlacedFeature>> extra = new java.util.ArrayList<>();
            for (Holder<PlacedFeature> holder : oreFeaturesInDimension()) {
                if (!listed.contains(featureId(holder))) {
                    extra.add(holder);
                    overrideChainWithoutBiomeFilter(holder.value());
                }
            }
            if (!extra.isEmpty()) {
                passes.add(extra);
            }
        }
        return passes;
    }

    private void placeCubeFeatures(LevelChunk chunk, LevelChunkSection section, int sectionY, Holder<Biome> cubeBiome) {
        int cubeMinY = sectionY << 4;
        int cubeMaxY = cubeMinY + 16;
        ChunkPos chunkPos = chunk.getPos();
        CubeGenAccess access = new CubeGenAccess(level, this, generator.bandMinY(), generator.stratumHeight());
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        long seed = level.getSeed();
        double bonus = CubicConfig.ORE_DEPTH_BONUS.get();

        useFeatureCacheFor(chunkPos, cubeBiome);
        java.util.List<java.util.List<Holder<PlacedFeature>>> steps = featurePassesFor(cubeBiome);
        int stepIndex = 0;
        for (java.util.List<Holder<PlacedFeature>> stepFeatures : steps) {
            int innerIndex = 0;
            for (Holder<PlacedFeature> holder : stepFeatures) {
                PlacedFeature placed = holder.value();
                // A geode replaces a volume: its shell is tuff and calcite and its inside is a
                // hollow sphere. Vanilla has about twelve of them per column in the sixty-four
                // blocks of its deepslate layer; repeating that rate over thousands of blocks of
                // depth fills the modded range with geode shell and reads as a world made of
                // geodes. Volume features stay where vanilla puts them unless a pack asks for
                // them to be tiled.
                if (!CubicConfig.TILE_VOLUME_FEATURES.get() && isVolumeFeature(holder)) {
                    recordFeatureSkipped(holder, "skipped: volume feature (geode), kept inside the band");
                    innerIndex++;
                    continue;
                }
                if (!isStratumEligible(placed)) {
                    if (hasHeightmapPlacement(placed)) {
                        recordFeatureSkipped(holder, "skipped: anchored to the terrain surface (heightmap placement)");
                        innerIndex++;
                        continue;
                    }
                    if (isOreOrDiskFeature(holder)) {
                        // An ore with no vertical placement modifier of its own - which is how a lot of
                        // modded ores are declared: a count, maybe in_square, a biome check, and the
                        // height left to the generation context. There is nothing to tile, so run it
                        // for this cube with the depth bonus applied, which is the same path an
                        // unmeasurable pass already takes. Excluding these was why some modded ores
                        // never appeared below the band at all.
                        spanOfFeature.put(placed, -1);
                        vminOfFeature.put(placed, cubeMinY);
                    } else {
                        recordFeatureSkipped(holder, "skipped: no height-range placement to tile");
                        innerIndex++;
                        continue;
                    }
                }
                // lava policy: vanilla lava springs must not place lava outside the near-floor zone
                if (isLavaSpring(holder) && cubeMinY > lavaZoneTop()) {
                    recordFeatureSkipped(holder, "skipped: lava policy (near-floor zone only)");
                    innerIndex++;
                    continue;
                }
                long producedThisFeature = 0L;
                long placedThisFeature = 0L;
                try {
                    PlacementContext context = new PlacementContext(access, gen, Optional.of(placed));
                    long featureSeed = seed + chunkPos.pack()
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
                    if (span < 0) {
                        // Not tileable: run it for this cube, which is what the pipeline did before
                        // passes were cached, and keep only what lands inside this cube. The depth
                        // bonus still applies, so these features get denser lower down like any other.
                        int stratum = Math.max(0, Math.floorDiv(
                                generator.bandMinY() - cubeMaxY, generator.stratumHeight()));
                        long placedHere = runFeatureInPlace(holder, placed, access, gen, context,
                                chunkPos, featureSeed, 1.0 + stratum * bonus, cubeMinY, cubeMaxY);
                        recordFeatureNote(holder, placedHere > 0L ? "ran in place"
                                : "ran in place, placed nothing here");
                        recordFeatureStat(holder, 1L, placedHere);
                        if (this.profileStratum >= 0) {
                            this.profilePlaced[this.profileStratum] += placedHere;
                        }
                        innerIndex++;
                        continue;
                    }
                    if (span == 0) {
                        recordFeatureSkipped(holder, "skipped: its own range reaches nowhere below the band");
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
                        recordFeatureSkipped(holder, "skipped: no tile covers this cube");
                        innerIndex++;
                        continue;
                    }
                    // A vanilla pass scatters its blobs over the whole band height; tiling them by
                    // the native span would therefore multiply density by band height / span. Scale
                    // the count back by that ratio so depth starts at band density, then grow it per
                    // stratum below the band (oreDepthBonusPerStratum).
                    // Density comes from the tile, not from the cube that happened to ask for it
                    // first: the count of a pass is scaled by which stratum that tile covers, so ore
                    // density rises with depth as configured while staying independent of the order
                    // the cubes of a column are generated in.
                    double scale = (double) span / (double) generator.stratumHeight();
                    recordFeatureReach(holder, vmin, span, tMax - tMin + 1, scale);
                    for (int tile = tMin; tile <= tMax; tile++) {
                        int tileBase = vmin - tile * span;
                        int stratum = Math.max(0, Math.floorDiv(
                                generator.bandMinY() - (tileBase + span), generator.stratumHeight()));
                        double multiplier = scale * (1.0 + stratum * bonus);
                        long key = ((long) stepIndex << 45) | ((long) innerIndex << 36)
                                | (tile & 0xffffffffL);
                        long[] pass = this.featureTilePasses.get(key);
                        if (pass == null) {
                            pass = evaluateFeatureTile(holder, placed, access, gen, context, chunkPos,
                                    tile, tileBase, span, multiplier, featureSeed);
                            this.profileFeatureEvals++;
                            if (pass != null) {
                                this.featureTilePasses.put(key, pass);
                            }
                        }
                        if (pass == null) {
                            continue;
                        }
                        long placedHere = 0;
                        producedThisFeature += pass.length;
                        for (int i = 0; i < pass.length; i++) {
                            long packed = pass[i];
                            int xOff = (int) (packed & 0x7FFL);
                            if ((xOff & 0x400) != 0) {
                                xOff -= 0x800;
                            }
                            int zOff = (int) ((packed >> 11) & 0x7FFL);
                            if ((zOff & 0x400) != 0) {
                                zOff -= 0x800;
                            }
                            int y = tileBase + (int) (packed >> 22);
                            if (this.profileStratum >= 0) {
                                this.profileCandidates[this.profileStratum]++;
                            }
                            if (y < cubeMinY || y >= cubeMaxY) {
                                continue;
                            }
                            BlockPos target = new BlockPos(
                                    chunkPos.getMinBlockX() + xOff, y, chunkPos.getMinBlockZ() + zOff);
                            RandomSource placeRandom = RandomSource.create(featureSeed
                                    ^ (tile * 0x9E3779B97F4A7C15L) ^ (i * 0x2545F4914F6CDD1DL));
                            try {
                                holder.value().feature().value().place(access, gen, placeRandom, target);
                                placedHere++;
                            } catch (Throwable e) {
                                if (complainedFeatureFailures.add(
                                        holder.value().feature().value().getClass())) {
                                    CubicChunks.LOGGER.warn(
                                            "[CubicChunks] deep feature placement failed (suppressing repeats): {}",
                                            e.toString());
                                }
                            }
                        }
                        placedThisFeature += placedHere;
                        if (this.profileStratum >= 0) {
                            this.profilePlaced[this.profileStratum] += placedHere;
                        }
                    }
                    if (producedThisFeature == 0L && span > 0 && !isOreOrDiskFeature(holder)) {
                        // Not one position from this feature's own band survived for this column, so
                        // anchor it in the cube instead. Note this is keyed on the pass yielding
                        // nothing at all: a sparse feature that produced positions and simply kept none
                        // for this cube is behaving correctly, and forcing one here would inflate
                        // density (it did, once: tuff jumped from 1.6 to 155 per 1000 blocks). pick a spot inside this cube and let the feature's own
                        // remaining modifiers (surface scans, offsets, biome check) decide whether it
                        // fits there. Without this, features whose height anchoring only resolves
                        // against a generated world - the cave-biome decorations: dripstone clusters,
                        // pointed and large dripstone, cave vines, spore blossoms, rooted azalea,
                        // lush clay and vegetation, classic vines, sculk veins and patches, glow
                        // lichen - produced nothing at any depth, so a biome's identity had none of
                        // its contents.
                        placedThisFeature = runFeatureInCube(holder, placed, access, gen, context,
                                chunkPos, featureSeed, cubeMinY, cubeMaxY,
                                Math.max(0, Math.floorDiv(generator.bandMinY() - cubeMaxY,
                                        generator.stratumHeight())));
                        recordFeatureNote(holder, placedThisFeature > 0L
                                ? "placed by the in-cube fallback" : "in-cube fallback placed nothing here");
                    }
                } catch (Throwable t) {
                    if (complainedFeatureFailures.add(placed.getClass())) {
                        CubicChunks.LOGGER.warn(
                                "[CubicChunks] deep feature planning failed (suppressing repeats): {}",
                                t.toString());
                    }
                }
                if (producedThisFeature > 0L && placedThisFeature == 0L) {
                    recordFeatureNote(holder,
                            "produced positions in this column, but none fell inside this cube");
                } else if (producedThisFeature > 0L) {
                    recordFeatureNote(holder, "ran");
                } else if (this.profileCost) {
                    recordFeatureSkipped(holder, "its own height anchoring produced no position");
                }
                recordFeatureStat(holder, producedThisFeature, placedThisFeature);
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
        BlockPos base = new BlockPos(chunkPos.getMinBlockX(), generator.bandMinY(), chunkPos.getMinBlockZ());
        boolean heightAnchored = false;
        boolean everyLayer = false;
        WorldGenState.CURRENT_MULTIPLIER.set(32.0);
        try {
            for (PlacementModifier modifier : placementChain(placed)) {
                if (modifier instanceof net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement) {
                    // This one iterates the build range itself; its reach is the whole band.
                    everyLayer = true;
                    continue;
                }
                if (!(modifier instanceof HeightRangePlacement)) {
                    // Count, rarity, biome and environment filters decide *whether* and *how often*,
                    // never how deep a feature can reach. Sampling the chain as a whole used to let
                    // them decide: a 1-in-N rarity gate, or an environment scan looking for air in a
                    // column that is not generated yet, produced no positions at all, the measurement
                    // came back as "reaches nowhere", and the feature was then never tiled below the
                    // band for the rest of the session. That is how rare ores - vanilla's large
                    // diamonds, and every rarity-gated ore and geode added by other mods - vanished
                    // from the deep world.
                    continue;
                }
                heightAnchored = true;
                // HeightRangePlacement rewrites the incoming Y and returns a single position, so
                // one call is one sample of the provider. Asking it for 4096 positions in one go
                // measured the provider once and reported a reach of 16 blocks for every feature,
                // which broke the tiling period, the count multiplier and - with the in-cube
                // fallback - put a geode in every cube.
                for (int sample = 0; sample < REACH_SAMPLES; sample++) {
                    BlockPos sampled = modifier.getPositions(context, rand, base).findFirst().orElse(null);
                    if (sampled == null) {
                        break;
                    }
                    int y = sampled.getY();
                    if (y < bounds[0]) {
                        bounds[0] = y;
                    }
                    if (y > bounds[1]) {
                        bounds[1] = y;
                    }
                }
            }
        } finally {
            WorldGenState.CURRENT_MULTIPLIER.set(1.0);
        }
        if (everyLayer && !heightAnchored) {
            bounds[0] = generator.bandMinY();
            bounds[1] = generator.bandMinY() + generator.stratumHeight() - 1;
        }
        if (!heightAnchored && !everyLayer) {
            return 0L;
        }
        int vmin = bounds[0];
        int vmax = bounds[1];
        if (vmax == Integer.MIN_VALUE && heightAnchored) {
            // The feature anchors itself to something only the real world can answer - a scan
            // through the terrain, or a height range expressed against a surface that does not exist
            // for a synthetic base position. Its reach cannot be measured here, so it must be run
            // in place per cube rather than tiled and cached.
            return -1L;
        }
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

    /** How many samples a feature's height range is measured over (tails matter for tiling). */
    private static final int REACH_SAMPLES = 4096;

    /**
     * Underground features only: those anchored to a height range (or to every layer of the build
     * range). Features anchored to the terrain surface through a heightmap belong to the world's
     * outer surface and are deliberately not repeated through the deep world.
     */
    private static boolean isStratumEligible(PlacedFeature placed) {
        boolean anchored = false;
        for (PlacementModifier modifier : placementChain(placed)) {
            if (modifier instanceof HeightmapPlacement) {
                return false;
            }
            if (modifier instanceof HeightRangePlacement
                    || modifier instanceof net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement) {
                anchored = true;
            }
        }
        return anchored;
    }

}
