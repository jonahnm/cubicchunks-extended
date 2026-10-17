package dev.cubicchunks.mixin;

import dev.cubicchunks.CubicConfig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lava policy for extended worlds: below y=-54 vanilla floods every aquifer with lava, which at
 * -2048 depth would make the deep world an unmineable lava sea. Instead, lava exists only near
 * the bottom of the modded range ({@code worldMinY .. worldMinY + lavaNearFloorDepth}); everywhere
 * else below -54, aquifer shapes are kept but lava becomes water pockets (local aquifer) or dry
 * cave air (the global-picker shortcut). The vanilla band above -54 is untouched, and the nether
 * (disabled aquifers) is unaffected.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer")
public abstract class NoiseBasedAquiferMixin {

    /** Lava is kept only at or below this Y for the current world floor. */
    private static int lavaZoneTop() {
        Integer floor = CubicConfig.extensionMinY();
        if (floor == null) {
            return Integer.MIN_VALUE;
        }
        return floor + CubicConfig.LAVA_NEAR_FLOOR_DEPTH.get();
    }

    @Inject(method = "computeFluid", at = @At("RETURN"), cancellable = true)
    private void cubicchunks$deepFluidPolicy(int x, int y, int z, CallbackInfoReturnable<Aquifer.FluidStatus> cir) {
        if (y >= -54) {
            return; // vanilla band keeps vanilla rules
        }
        Aquifer.FluidStatus status = cir.getReturnValue();
        if (status == null) {
            return;
        }
        BlockState fluid = ((FluidStatusAccessor) (Object) status).cubicchunks$fluidType();
        if (fluid != null && fluid.is(Blocks.LAVA) && y > lavaZoneTop()) {
            // dry: level y + cave air keeps the pocket shape without perched water columns
            cir.setReturnValue(new Aquifer.FluidStatus(y, Blocks.CAVE_AIR.defaultBlockState()));
        }
    }

    @Inject(method = "computeSubstance", at = @At("RETURN"), cancellable = true)
    private void cubicchunks$deepDryCaves(DensityFunction.FunctionContext context, double density, CallbackInfoReturnable<BlockState> cir) {
        int y = context.blockY();
        if (y >= -54) {
            return; // vanilla band keeps vanilla rules
        }
        BlockState state = cir.getReturnValue();
        if (state != null && state.is(Blocks.LAVA) && y > lavaZoneTop()) {
            cir.setReturnValue(Blocks.CAVE_AIR.defaultBlockState());
        }
    }
}
