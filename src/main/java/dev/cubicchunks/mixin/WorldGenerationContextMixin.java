package dev.cubicchunks.mixin;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WorldGenerationContext caches min/height from the chunk generator's *settings*; take them from
 * the (possibly extended) level accessor instead so feature placement sees true world bounds.
 */
@Mixin(WorldGenerationContext.class)
public abstract class WorldGenerationContextMixin {

    @Shadow @Final @Mutable private int minY;
    @Shadow @Final @Mutable private int height;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cubicchunks$useAccessor(ChunkGenerator generator, LevelHeightAccessor accessor, CallbackInfo ci) {
        this.minY = accessor.getMinY();
        this.height = accessor.getHeight();
    }
}
