package dev.cubicchunks.mixin;

import dev.cubicchunks.CubicConfig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.SurfaceRules.Context;
import net.minecraft.world.level.levelgen.SurfaceRules.SurfaceRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Relocates the bedrock floor for extended worlds: bedrock exists only near the configured world
 * floor (when {@code deepBedrockFloor} is on); everywhere else — including the vanilla band's
 * y=-64 floor — bedrock is replaced with deepslate so the deep world is seamlessly reachable.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$BlockRuleSource")
public abstract class StateRuleMixin {

    @Shadow
    private BlockState resultState;

    @Inject(method = "apply", at = @At("RETURN"), cancellable = true)
    private void cubicchunks$relocateBedrock(Context context, CallbackInfoReturnable<SurfaceRule> cir) {
        if (!this.resultState.is(Blocks.BEDROCK)) {
            return;
        }
        ChunkAccess chunk = ((SurfaceRulesContextAccessor) (Object) context).cubicchunks$chunk();
        if (chunk == null) {
            return;
        }
        // Only extended overworld-style chunks (nether/end chunks keep their vanilla bedrock).
        if (chunk.getMinY() >= -64) {
            return;
        }
        SurfaceRule original = cir.getReturnValue();
        boolean keepFloor = CubicConfig.DEEP_BEDROCK_FLOOR.get();
        int floor = chunk.getMinY();
        cir.setReturnValue((x, y, z) -> {
            BlockState result = original.tryApply(x, y, z);
            if (result != null && result.is(Blocks.BEDROCK)) {
                if (keepFloor && y < floor + 5) {
                    return result; // bedrock floor at the very bottom of the world
                }
                return Blocks.DEEPSLATE.defaultBlockState();
            }
            return result;
        });
    }
}
