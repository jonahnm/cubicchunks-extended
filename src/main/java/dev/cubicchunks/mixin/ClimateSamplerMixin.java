package dev.cubicchunks.mixin;

import dev.cubicchunks.CubicConfig;
import dev.cubicchunks.worldgen.WorldGenState;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Maps deep coordinates (below the vanilla band) onto vanilla cave-biome climate parameters while
 * extension cubes sample their biomes. Without this, climate extrapolation below the band yields
 * nonsense biomes (e.g. frozen oceans underground). Only active while the extension generator is
 * sampling ({@link WorldGenState#REMAP_DEEP_BIOMES}), so all other biome lookups stay vanilla.
 */
@Mixin(Climate.Sampler.class)
public abstract class ClimateSamplerMixin {

    @Inject(method = "sample(III)Lnet/minecraft/world/level/biome/Climate$TargetPoint;", at = @At("RETURN"), cancellable = true)
    private void cubicchunks$remapDeep(int x, int y, int z, CallbackInfoReturnable<Climate.TargetPoint> cir) {
        if (!WorldGenState.REMAP_DEEP_BIOMES.get() || !CubicConfig.REMAP_DEEP_BIOMES.get()) {
            return;
        }
        Climate.TargetPoint original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        int blockY = y * 4;
        if (blockY >= -64) {
            return; // vanilla band untouched
        }
        long temp = original.temperature();
        long humid = original.humidity();
        long cont = original.continentalness();
        long erosion = original.erosion();
        long depth = original.depth();
        long weird = original.weirdness();
        // Push deep samples into the parameter ranges of vanilla underground biomes (dripstone
        // caves, lush caves, deep dark), biased by the sample's own erosion/continentalness.
        if (erosion < -3750L && cont >= -1100L) {
            depth = 11000L;
        } else {
            depth = 5000L;
            if (humid >= 0L) {
                humid = 8000L;
            } else {
                cont = 9000L;
            }
        }
        cir.setReturnValue(new Climate.TargetPoint(temp, humid, cont, erosion, depth, weird));
    }
}
