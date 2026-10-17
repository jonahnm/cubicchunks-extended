package dev.cubicchunks.mixin;

import dev.cubicchunks.worldgen.WorldGenState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scales placement counts while the extension feature planner evaluates: a feature's placement pass
 * is re-tiled by its native vertical span below the band, so counts are scaled by span/band-height
 * to keep per-block density at the band's, times the configured growth per stratum. Vanilla band
 * decoration never sets the multiplier, so band feature counts stay vanilla-exact.
 */
@Mixin(CountPlacement.class)
public abstract class CountPlacementMixin {

    @Inject(method = "count", at = @At("RETURN"), cancellable = true)
    private void cubicchunks$scaleCount(RandomSource random, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        double multiplier = WorldGenState.CURRENT_MULTIPLIER.get();
        if (multiplier != 1.0) {
            cir.setReturnValue((int) Math.round(cir.getReturnValue() * multiplier));
        }
    }
}
