package dev.cubicchunks.core;

import java.util.ArrayList;
import java.util.List;

import dev.cubicchunks.CubicConfig;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * Creates "deep" structure starts: for every structure that generated in the vanilla band of a
 * cubic dimension, the pieces are NBT-copied and translated down by each stratum period, so
 * vanilla structures (ancient cities, villages, ...) also generate below the band. All strata
 * are merged into ONE combined start per structure per column (the structure marks map is keyed
 * by structure), which keeps references, the Beardifier terrain adaptation and piece placement
 * working through vanilla code paths.
 */
public final class DeepStructures {

    /** Vanilla overworld band height (the stratum period for overworld-pattern dimensions). */
    private static final int BAND_HEIGHT = 384;
    private static final int BAND_MIN = -64;

    /** Instrumentation: how often vanilla's structure stage ran, and what this produced. */
    public static final java.util.concurrent.atomic.AtomicLong STRUCTURE_STAGE_CALLS =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DEEP_STARTS_CREATED =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong DEEP_PIECES_COPIED =
            new java.util.concurrent.atomic.AtomicLong();
    /** Counted at the moment vanilla's structure stage finishes for a chunk, i.e. before anything
     * this mod later does to that chunk. Comparing these with what a later scan of live chunks
     * reports tells starts-not-created apart from starts-not-retained. */
    public static final java.util.concurrent.atomic.AtomicLong STARTS_SEEN_AT_GENERATION =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong CHUNKS_WITH_STARTS_AT_GENERATION =
            new java.util.concurrent.atomic.AtomicLong();
    /** Transplanted pieces actually written into cubes, which is the number that matters. */
    public static final java.util.concurrent.atomic.AtomicLong TRANSPLANT_PIECES_PLACED =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong TRANSPLANT_SOURCE_LOOKUPS =
            new java.util.concurrent.atomic.AtomicLong();
    /** Source columns that were not loaded. Transplants are only read from loaded columns, so this
     * is the number of structures that were not placed because their source was out of range. */
    /** How many cubes had their terrain raised around a structure this mod intends to place. */
    public static final java.util.concurrent.atomic.AtomicLong BEARDED_CUBES =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong BEARD_RIGIDS =
            new java.util.concurrent.atomic.AtomicLong();
    /** Why the beard found nothing: no candidates, or candidates that asked for no adaptation. */
    public static final java.util.concurrent.atomic.AtomicLong BEARD_CANDIDATES =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong BEARD_NO_ADAPTATION_RECORDED =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong BEARD_ADAPTATION_NONE =
            new java.util.concurrent.atomic.AtomicLong();
    /** Pieces whose structure asked for no beard, given a mild one in the extension. */
    public static final java.util.concurrent.atomic.AtomicLong BEARD_ADAPTATION_ADDED =
            new java.util.concurrent.atomic.AtomicLong();
    /** Air counted in a handful of bearded cubes, to compare against the stratum average. */
    public static final java.util.concurrent.atomic.AtomicLong BEARD_SAMPLE_COUNT =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong BEARD_SAMPLE_AIR =
            new java.util.concurrent.atomic.AtomicLong();
    /** Control: cubes in columns that do hold a structure, in a cube that does not. Same seed, same
     * column, same neighbourhood - the only difference is whether this cube's terrain was raised. */
    /** Cubes that are about to receive a structure, sampled whether or not a beard was applied -
     * the same set of cubes in both configurations, on the same seed. */
    public static final java.util.concurrent.atomic.AtomicLong STRUCTURE_CUBE_SAMPLES =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong STRUCTURE_CUBE_AIR =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong CONTROL_SAMPLE_COUNT =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong CONTROL_SAMPLE_AIR =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong TRANSPLANT_SOURCES_UNLOADED =
            new java.util.concurrent.atomic.AtomicLong();
    /** Source columns handed to the chunk loader because they were not loaded. */
    public static final java.util.concurrent.atomic.AtomicLong TRANSPLANT_SOURCES_REQUESTED =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong TRANSPLANT_SOURCES_LOADED =
            new java.util.concurrent.atomic.AtomicLong();
    /** The height range structures actually land in, measured as they are generated. Extending a
     * dimension's build height also moves every surface-relative structure height, so this is how
     * to tell "structures are rare" apart from "structures are being placed at y=2048, where there
     * is no terrain". */
    public static volatile int GEN_MIN_Y = Integer.MAX_VALUE;
    public static volatile int GEN_MAX_Y = Integer.MIN_VALUE;
    public static volatile int GEN_PIECE_COUNT;
    /** Transplanted pieces grouped by the structure they came from, so a modded structure shows up
     * in its own right instead of being hidden inside "the feature works". */
    private static final java.util.Set<String> UNRECOGNISED_PIECE_TYPES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Pieces offered to cubes from the per-column index, grouped by structure name. This is the
     * answer to "which structures actually reach the deep world", as opposed to which ones exist. */
    public static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.atomic.AtomicLong> INDEXED_BY_STRUCTURE =
            new java.util.concurrent.ConcurrentHashMap<>();
    public static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.atomic.AtomicLong> TRANSPLANTED_BY_STRUCTURE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private DeepStructures() {}

    /**
     * A piece copy needs a serialization context, and the only place one is safely available is
     * vanilla's own structure stage (which is handed the registry and template manager). It is
     * stashed here so cube generation can reuse it instead of assembling one - building it during
     * generation from the level looked up neighbours and deadlocked the worldgen mailbox.
     */
    public static volatile StructurePieceSerializationContext SHARED_CONTEXT;

    public static void createDeepStarts(RegistryAccessHolder holder, StructureManager structureManager, ChunkAccess chunk) {
        // NOTE: holder.context() must be constructed with the SERVER's registry/template manager.
        if (!CubicConfig.DEEP_STRUCTURES.get()) {
            return;
        }
        Integer floor = CubicConfig.extensionMinY();
        if (floor == null || floor >= BAND_MIN) {
            return;
        }
        int maxStrata = (BAND_MIN - floor) / BAND_HEIGHT;
        if (maxStrata <= 0) {
            return;
        }
        STRUCTURE_STAGE_CALLS.incrementAndGet();
        SHARED_CONTEXT = holder.context();
        int present = 0;
        for (StructureStart start : chunk.getAllStarts().values()) {
            if (start != null && start.isValid()) {
                present++;
            }
        }
        if (present > 0) {
            CHUNKS_WITH_STARTS_AT_GENERATION.incrementAndGet();
            STARTS_SEEN_AT_GENERATION.addAndGet(present);
        }
        SectionPos sectionPos = SectionPos.bottomOf(chunk);
        ChunkPos chunkPos = chunk.getPos();
        // IMPORTANT: read the chunk's OWN structure marks only. structureManager.startsForStructure()
        // would load neighbor chunks from inside STRUCTURE_STARTS, deadlocking the worldgen mailbox
        // during spawn generation.
        List<StructureStart> deepStarts = new ArrayList<>();
        for (StructureStart start : chunk.getAllStarts().values()) {
            if (start == null || !start.isValid() || start.getPieces().isEmpty()) {
                continue;
            }
            for (StructurePiece piece : start.getPieces()) {
                int y0 = piece.getBoundingBox().minY();
                int y1 = piece.getBoundingBox().maxY();
                if (y0 < GEN_MIN_Y) {
                    GEN_MIN_Y = y0;
                }
                if (y1 > GEN_MAX_Y) {
                    GEN_MAX_Y = y1;
                }
                GEN_PIECE_COUNT++;
            }
            List<StructurePiece> combined = new ArrayList<>(start.getPieces());
            for (int stratum = 1; stratum <= maxStrata; stratum++) {
                int shift = -stratum * BAND_HEIGHT;
                for (StructurePiece piece : start.getPieces()) {
                    StructurePiece copy = copyTranslated(piece, holder, shift);
                    if (copy != null) {
                        combined.add(copy);
                    }
                }
            }
            if (combined.size() == start.getPieces().size()) {
                continue;
            }
            DEEP_STARTS_CREATED.incrementAndGet();
            DEEP_PIECES_COPIED.addAndGet(combined.size() - start.getPieces().size());
            StructureStart deep = new StructureStart(
                    start.getStructure(), chunkPos, start.getReferences(), new PiecesContainer(combined));
            deepStarts.add(deep);
        }
        for (StructureStart deep : deepStarts) {
            structureManager.setStartForStructure(sectionPos, deep.getStructure(), deep, chunk);
        }
    }

    /** Deep-copies a piece via its NBT round-trip, translating its bounding box (and pivot). */
    public static StructurePiece copyTranslated(StructurePiece piece, RegistryAccessHolder holder, int shift) {
        return copyTranslated(piece, holder, 0, shift, 0);
    }

    public static StructurePiece copyTranslated(
            StructurePiece piece, RegistryAccessHolder holder, int dx, int shift, int dz) {
        try {
            CompoundTag tag = piece.createTag(holder.context());
            shiftTag(tag, dx, shift, dz);
            String id = tag.getString("id").map(s -> s.toLowerCase(java.util.Locale.ROOT)).orElse("");
            Identifier rl = Identifier.parse(id);
            rl = rename(rl);
            var type = BuiltInRegistries.STRUCTURE_PIECE.getValue(rl);
            if (type == null) {
                // A modded structure whose piece type is not in the structure-piece registry would
                // otherwise disappear with nothing said about it, which is the one way this path can
                // quietly lose a structure a mod added. Named once per id so it is visible without
                // turning into spam.
                if (UNRECOGNISED_PIECE_TYPES.add(rl.toString())) {
                    dev.cubicchunks.CubicChunks.LOGGER.warn(
                            "[CubicChunks] no structure piece type registered for {} - a structure"
                                    + " using it will not be reproduced below the band", rl);
                }
                return null;
            }
            return type.load(holder.context(), tag);
        } catch (Throwable t) {
            dev.cubicchunks.CubicChunks.LOGGER.warn("[CubicChunks] piece translation failed for {}: {}",
                    piece.getClass().getSimpleName(), t.toString());
            return null;
        }
    }

    private static Identifier rename(Identifier rl) {
        // replicate PiecesContainer's legacy renames
        return switch (rl.getPath()) {
            case "nvi", "pcp", "bastionremnant", "runtime" -> Identifier.withDefaultNamespace("jigsaw");
            default -> rl;
        };
    }

    /**
     * Which band column a deep cube's structures come from. Each stratum uses its own shift and its
     * own orientation, derived from the world seed, so the deep world is not the band's structure
     * map pasted straight down: a column that has nothing in the band can host something a
     * stratum or two down, and the rate per stratum is the rate vanilla uses per 384 blocks.
     *
     * @param target   the column the cube belongs to
     * @param stratum  which 384-block slice below the band, 1 for the first
     * @param seed     the world seed
     */
    public static ChunkPos sourceColumnFor(ChunkPos target, int stratum, long seed) {
        long h = seed ^ (stratum * 0x9E3779B97F4A7C15L);
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        // Offsets stay inside a player's view distance on purpose. Reading a source column must
        // never block on a chunk load: asking for one from inside chunk generation deadlocks the
        // chunk executor waiting on the main thread, which is what took a 60-second tick and got
        // the server killed by the watchdog. Small offsets mean the source is usually already
        // loaded, so the lookup is a lookup and not a generation request.
        int offX = (int)Math.floorMod(h >>> 11, 21L) - 10;
        int offZ = (int)Math.floorMod(h >>> 40, 21L) - 10;
        // Offsets stay local on purpose, and the map is translated rather than rotated.
        // A source column is only ever read when it is already loaded: asking for one from
        // inside chunk generation blocks on the executor that is waiting on the main thread,
        // which is what put a 60-second tick on the server and had the watchdog kill it. A
        // rotation or mirror maps a distant column to a distant column, so almost nothing
        // would ever be available; a translation keeps the source within reach while still
        // giving each stratum its own structure map, different from the band's and from each
        // other stratum.
        return new ChunkPos(target.x() + offX, target.z() + offZ);
    }

    /**
     * Shifts a piece's "BB" bounding box and its "TP"/"POS" pivot, on all three axes.
     *
     * The horizontal part matters as much as the vertical one. A stratum's structure comes from a
     * different band column than the cube it is being placed into, so a Y-only shift leaves the
     * piece sitting over its source column and it never intersects the cube being generated -
     * measured, 66 pieces collected and 0 placed. Moving it by the same number of chunks it was
     * read from is what puts it in the cube.
     */
    private static void shiftTag(CompoundTag tag, int dx, int dy, int dz) {
        int[] bb = tag.getIntArray("BB").orElse(null);
        if (bb != null && bb.length >= 6) {
            // BB is [minX, minY, minZ, maxX, maxY, maxZ].
            bb[0] += dx;
            bb[1] += dy;
            bb[2] += dz;
            bb[3] += dx;
            bb[4] += dy;
            bb[5] += dz;
            tag.putIntArray("BB", bb);
        }
        for (String key : new String[] {"TP", "POS"}) {
            int[] piv = tag.getIntArray(key).orElse(null);
            if (piv != null && piv.length >= 3) {
                piv[0] += dx;
                piv[1] += dy;
                piv[2] += dz;
                tag.putIntArray(key, piv);
            }
        }
    }

    /** Thin holder so the serialization context can be built once per chunk. */
    public record RegistryAccessHolder(StructurePieceSerializationContext context) {}

    public static StructurePieceSerializationContext contextFor(net.minecraft.core.RegistryAccess registryAccess,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templateManager) {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        var resourceManager = server != null ? server.getResourceManager() : null;
        return new StructurePieceSerializationContext(resourceManager, registryAccess, templateManager);
    }

    /**
     * The registry name of a start's structure, for diagnostics only. The structure registry is not
     * in BuiltInRegistries, so it is reached through the level's own registry access - which also
     * means a modded structure reports under its own namespace, which is the point.
     */
    public static String structureKey(
            StructureStart start, net.minecraft.core.RegistryAccess registryAccess) {
        try {
            net.minecraft.core.Registry<net.minecraft.world.level.levelgen.structure.Structure> registry =
                    registryAccess.lookupOrThrow(
                            net.minecraft.core.registries.Registries.STRUCTURE);
            net.minecraft.resources.Identifier id = registry.getKey(start.getStructure());
            return id == null ? "[unregistered]" : id.toString();
        } catch (RuntimeException e) {
            return start.getStructure().getClass().getSimpleName();
        }
    }
}