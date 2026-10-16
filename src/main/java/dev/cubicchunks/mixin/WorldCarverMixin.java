package dev.cubicchunks.mixin;

import dev.cubicchunks.CubicConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Carvers place lava in everything they carve below their configured lava level (y=-54 for the
 * overworld) independently of the aquifer. Apply the same policy as the aquifer mixin: no lava
 * below -54 except near the bottom of the modded range, where carved tunnels keep their lava.
 */
@Mixin(WorldCarver.class)
public abstract class WorldCarverMixin {

    @Inject(method = "getCarveState", at = @At("RETURN"), cancellable = true)
    private void cubicchunks$carverLavaPolicy(
            CarvingContext context, CarverConfiguration config, BlockPos pos, net.minecraft.world.level.levelgen.Aquifer aquifer,
            CallbackInfoReturnable<BlockState> cir) {
        int y = pos.getY();
        if (y >= -54) {
            return;
        }
        BlockState state = cir.getReturnValue();
        if (state == null || !state.is(Blocks.LAVA)) {
            return;
        }
        Integer floor = CubicConfig.extensionMinY();
        if (floor == null) {
            return;
        }
        if (y > floor + CubicConfig.LAVA_NEAR_FLOOR_DEPTH.get()) {
            cir.setReturnValue(Blocks.CAVE_AIR.defaultBlockState());
        }
    }
}
