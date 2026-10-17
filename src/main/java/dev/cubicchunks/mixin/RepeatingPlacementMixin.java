package dev.cubicchunks.mixin;

import dev.cubicchunks.worldgen.WorldGenState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import net.minecraft.world.level.levelgen.placement.NoiseThresholdCountPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Companion of {@link CountPlacementMixin}: scales the remaining repeating placement types
 * (noise-based counts, noise-threshold counts) while the extension feature planner is evaluating.
 */
@Mixin({NoiseBasedCountPlacement.class, NoiseThresholdCountPlacement.class})
public abstract class RepeatingPlacementMixin {

    @Inject(method = "count", at = @At("RETURN"), cancellable = true)
    private void cubicchunks$scaleCount(RandomSource random, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        double multiplier = WorldGenState.CURRENT_MULTIPLIER.get();
        if (multiplier != 1.0) {
            cir.setReturnValue((int) Math.round(cir.getReturnValue() * multiplier));
        }
    }
}
