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
    /** The height range structures actually land in, measured as they are generated. Extending a
     * dimension's build height also moves every surface-relative structure height, so this is how
     * to tell "structures are rare" apart from "structures are being placed at y=2048, where there
     * is no terrain". */
    public static volatile int GEN_MIN_Y = Integer.MAX_VALUE;
    public static volatile int GEN_MAX_Y = Integer.MIN_VALUE;
    public static volatile int GEN_PIECE_COUNT;

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
        try {
            CompoundTag tag = piece.createTag(holder.context());
            shiftTagY(tag, shift);
            String id = tag.getString("id").map(s -> s.toLowerCase(java.util.Locale.ROOT)).orElse("");
            Identifier rl = Identifier.parse(id);
            rl = rename(rl);
            var type = BuiltInRegistries.STRUCTURE_PIECE.getValue(rl);
            if (type == null) {
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
        int offX = (int)Math.floorMod(h >>> 11, 512L) - 256;
        int offZ = (int)Math.floorMod(h >>> 40, 512L) - 256;
        // Rotate or mirror the map per stratum, so consecutive slices are not translations of one
        // another. Four orientations are enough: the structure maps are not rotationally symmetric.
        int x = target.x();
        int z = target.z();
        return switch (stratum & 3) {
            case 1 -> new ChunkPos(x - offX + offZ, -z - offZ - offX);
            case 2 -> new ChunkPos(-x + offX, z + offZ);
            case 3 -> new ChunkPos(x + offZ, -z + offX);
            default -> new ChunkPos(x + offX, z + offZ);
        };
    }

    /** Shifts the Y components of the piece's "BB" (int array of 6) and "TP"/"POS" pivots. */
    private static void shiftTagY(CompoundTag tag, int shift) {
        int[] bb = tag.getIntArray("BB").orElse(null);
        if (bb != null) {
            if (bb.length >= 6) {
                bb[1] += shift;
                bb[4] += shift;
                tag.putIntArray("BB", bb);
            }
        }
        for (String key : new String[] {"TP", "POS"}) {
            int[] piv = tag.getIntArray(key).orElse(null);
            if (piv != null) {
                if (piv.length >= 3) {
                    piv[1] += shift;
                    tag.putIntArray(key, piv);
                }
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
}
