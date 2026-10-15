package dev.cubicchunks.mixin;

import dev.cubicchunks.CubicConfig;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scales *negative* absolute vertical anchors proportionally to the world depth: an ore feature
 * anchored at y=-80 in a vanilla-depth world anchors at y=-80*minY/-64 below the band when the
 * world is deeper. Positive anchors stay put, so surface-height behavior is untouched.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.VerticalAnchor$Absolute")
public abstract class AbsoluteVerticalAnchorMixin {

    @Shadow private int y;

    @Inject(method = "resolveY", at = @At("HEAD"), cancellable = true)
    private void cubicchunks$scaleAnchor(WorldGenerationContext context, CallbackInfoReturnable<Integer> cir) {
        Integer minY = CubicConfig.extensionMinY();
        if (minY == null) {
            return;
        }
        // Only scale while the extension feature planner is evaluating placements; vanilla band
        // decoration never sets the multiplier, so band feature anchors stay vanilla-exact.
        double multiplier = dev.cubicchunks.worldgen.WorldGenState.CURRENT_MULTIPLIER.get();
        if (multiplier <= 1.0) {
            return;
        }
        int anchorY = this.y;
        int contextMinY = context.getMinGenY();
        if (contextMinY < -64 && anchorY < 0) {
            cir.setReturnValue((int) Math.round(anchorY * (contextMinY / -64.0)));
        }
    }
}
