package dev.cubicchunks.core;

import java.util.ArrayList;
import java.util.List;

import dev.cubicchunks.CubicConfig;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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

    private DeepStructures() {}

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
            StructureStart deep = new StructureStart(
                    start.getStructure(), chunkPos, start.getReferences(), new PiecesContainer(combined));
            deepStarts.add(deep);
        }
        for (StructureStart deep : deepStarts) {
            structureManager.setStartForStructure(sectionPos, deep.getStructure(), deep, chunk);
        }
    }

    /** Deep-copies a piece via its NBT round-trip, translating its bounding box (and pivot). */
    private static StructurePiece copyTranslated(StructurePiece piece, RegistryAccessHolder holder, int shift) {
        try {
            CompoundTag tag = piece.createTag(holder.context());
            shiftTagY(tag, shift);
            String id = tag.getString("id").toLowerCase(java.util.Locale.ROOT);
            ResourceLocation rl = ResourceLocation.parse(id);
            rl = rename(rl);
            var type = BuiltInRegistries.STRUCTURE_PIECE.get(rl);
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

    private static ResourceLocation rename(ResourceLocation rl) {
        // replicate PiecesContainer's legacy renames
        return switch (rl.getPath()) {
            case "nvi", "pcp", "bastionremnant", "runtime" -> ResourceLocation.withDefaultNamespace("jigsaw");
            default -> rl;
        };
    }

    /** Shifts the Y components of the piece's "BB" (int array of 6) and "TP"/"POS" pivots. */
    private static void shiftTagY(CompoundTag tag, int shift) {
        if (tag.contains("BB", Tag.TAG_INT_ARRAY)) {
            int[] bb = tag.getIntArray("BB");
            if (bb.length >= 6) {
                bb[1] += shift;
                bb[4] += shift;
                tag.putIntArray("BB", bb);
            }
        }
        for (String key : new String[] {"TP", "POS"}) {
            if (tag.contains(key, Tag.TAG_INT_ARRAY)) {
                int[] piv = tag.getIntArray(key);
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
