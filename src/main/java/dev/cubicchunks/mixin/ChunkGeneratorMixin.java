package dev.cubicchunks.mixin;

import dev.cubicchunks.CubicConfig;
import dev.cubicchunks.core.DeepStructures;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After vanilla registers structure starts for a chunk, create "deep" copies of each start whose
 * pieces are translated down per stratum, so vanilla structures also generate below the band.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @Inject(method = "createStructures", at = @At("TAIL"))
    private void cubicchunks$deepStructures(
            RegistryAccess registryAccess,
            ChunkGeneratorStructureState structureState,
            StructureManager structureManager,
            ChunkAccess chunk,
            StructureTemplateManager templateManager,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> levelKey,
            CallbackInfo ci) {
        dev.cubicchunks.core.DeepStructures.SHARED_STRUCTURE_STATE = structureState;
        if (!CubicConfig.DEEP_STRUCTURES.get() || CubicConfig.extensionMinY() == null) {
            return;
        }
        DeepStructures.createDeepStarts(new DeepStructures.RegistryAccessHolder(
                DeepStructures.contextFor(registryAccess, templateManager)),
                structureManager, chunk);
    }
}
