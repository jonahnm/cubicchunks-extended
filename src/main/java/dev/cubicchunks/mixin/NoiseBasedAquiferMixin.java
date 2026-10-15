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
 * Vanilla floods every air pocket below y=-54 with lava aquifers; extended to any depth that
 * means the entire deep world is lava. Below the band we keep vanilla's aquifer *shapes* but
 * swap lava for water pockets (or dry cave air), matching the shallow band's feel. The vanilla
 * band (y >= -64) is untouched.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer")
public abstract class NoiseBasedAquiferMixin {

    /**
     * Local aquifer fluid: below the band, vanilla's erosion-based lava choice becomes water at
     * sea level (like shallow aquifers).
     */
    @Inject(method = "computeFluid", at = @At("RETURN"), cancellable = true)
    private void cubicchunks$deepWaterNotLava(int x, int y, int z, CallbackInfoReturnable<Aquifer.FluidStatus> cir) {
        if (y >= -64) {
            return; // vanilla band keeps vanilla lava rules
        }
        Aquifer.FluidStatus status = cir.getReturnValue();
        if (status == null) {
            return;
        }
        BlockState fluid = ((FluidStatusAccessor) (Object) status).cubicchunks$fluidType();
        if (fluid != null && fluid.is(Blocks.LAVA)) {
            cir.setReturnValue(new Aquifer.FluidStatus(y, Blocks.WATER.defaultBlockState()));
        }
    }

    /**
     * The global fluid picker shortcut returns lava directly for any pocket below y=-54; below
     * the band that becomes dry cave air (water pockets still arrive via the local aquifer).
     */
    @Inject(method = "computeSubstance", at = @At("RETURN"), cancellable = true)
    private void cubicchunks$deepDryCaves(DensityFunction.FunctionContext context, double density, CallbackInfoReturnable<BlockState> cir) {
        if (context.blockY() >= -64) {
            return; // vanilla band keeps vanilla lava rules
        }
        BlockState state = cir.getReturnValue();
        if (state != null && state.is(Blocks.LAVA)) {
            cir.setReturnValue(Blocks.CAVE_AIR.defaultBlockState());
        }
    }
}
