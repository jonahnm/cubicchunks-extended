package dev.cubicchunks.mixin;

import dev.cubicchunks.CubicConfig;
import dev.cubicchunks.worldgen.WorldGenState;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands the climate samples of extension cubes to {@link dev.cubicchunks.worldgen.DeepBiomeProfile},
 * which maps the depth below the vanilla band onto vanilla cave biomes. Without this, climate
 * extrapolation below the band yields nonsense biomes (e.g. frozen oceans underground), and with
 * only a flat remap the whole deep world ends up one biome. Only active while the extension
 * generator is sampling ({@link WorldGenState#REMAP_DEEP_BIOMES}), so all other biome lookups -
 * including everything inside the vanilla band - stay vanilla.
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
        int bandMinY = WorldGenState.REMAP_BAND_MIN_Y.get();
        if (bandMinY == 0 || blockY >= bandMinY) {
            return; // vanilla band untouched
        }
        cir.setReturnValue(dev.cubicchunks.worldgen.DeepBiomeProfile.apply(
                original, blockY, bandMinY, WorldGenState.REMAP_WORLD_MIN_Y.get()));
    }
}
