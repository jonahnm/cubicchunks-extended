package dev.cubicchunks.mixin;

import dev.cubicchunks.CubicConfig;
import dev.cubicchunks.worldgen.DeepBiomeProfile;
import dev.cubicchunks.worldgen.WorldGenState;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Samples extension cubes at a virtual height inside the vanilla band, so the deep world is
 * assigned biomes by vanilla's own picker over the dimension's real biome list. See
 * {@link dev.cubicchunks.worldgen.DeepBiomeProfile} for why this maps the coordinate instead of
 * naming a biome. Only active while the extension generator is sampling
 * ({@link WorldGenState#REMAP_DEEP_BIOMES}), so every other biome lookup - including everything
 * inside the vanilla band - is vanilla's own.
 *
 * <p>The mapped coordinate is inside the band, so the recursive call into {@code sample} lands in
 * the branch that leaves the position to vanilla and the mapping terminates in one step.
 */
@Mixin(Climate.Sampler.class)
public abstract class ClimateSamplerMixin {

    @Inject(method = "sample(III)Lnet/minecraft/world/level/biome/Climate$TargetPoint;",
            at = @At("HEAD"),
            cancellable = true)
    private void cubicchunks$mapDeepCoordinate(int quartX, int quartY, int quartZ,
            CallbackInfoReturnable<Climate.TargetPoint> cir) {
        if (!WorldGenState.REMAP_DEEP_BIOMES.get() || !CubicConfig.REMAP_DEEP_BIOMES.get()) {
            return;
        }
        int bandMinY = WorldGenState.REMAP_BAND_MIN_Y.get();
        if (bandMinY == 0) {
            return;
        }
        Climate.TargetPoint mapped = DeepBiomeProfile.sample((Climate.Sampler)(Object)this, quartX, quartY,
                quartZ, bandMinY, WorldGenState.REMAP_BAND_HEIGHT.get(),
                WorldGenState.REMAP_WORLD_MIN_Y.get());
        if (mapped != null) {
            cir.setReturnValue(mapped);
            cir.cancel();
        }
    }
}
