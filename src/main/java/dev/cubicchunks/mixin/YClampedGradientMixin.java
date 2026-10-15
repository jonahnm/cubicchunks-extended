package dev.cubicchunks.mixin;

import dev.cubicchunks.CubicConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Vanilla's density-function gradients clamp to constant below y=-64, which would make the deep
 * world dead solid. This re-baselines the two terrain gradients so the vanilla cave machinery
 * keeps working at depth, and relocates the band-edge fades to the configured world bounds.
 * (Y-coordinate behavior of vanilla NoiseRouterData, extended.)
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$YClampedGradient")
public abstract class YClampedGradientMixin {

    @Shadow private int fromY;
    @Shadow private int toY;
    @Shadow private double fromValue;
    @Shadow private double toValue;

    /**
     * Vanilla: clamp to {@code fromValue}/{@code toValue} outside [fromY, toY].
     * Extension: for the big terrain gradients, keep a deep-friendly value below the band;
     * for band-edge fades, translate the fade window to the configured world floor/ceiling.
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext context) {
        int y = context.blockY();
        Integer minY = CubicConfig.extensionMinY();
        Integer maxY = CubicConfig.extensionMaxY();
        if (minY == null || maxY == null) {
            return vanilla(y);
        }

        boolean terrainGradient = this.fromY == -64 && this.toY == 320;
        if (terrainGradient && y < -64) {
            // depth gradient (1.5 -> -1.5) would clamp the deep world to "densest possible";
            // rebaseline to 0.8 (and similar for the 8.0 -> -40.0 spaghetti thickness gradient)
            double target;
            if (this.fromValue == 1.5 && this.toValue == -1.5) {
                target = 0.8;
            } else if (this.fromValue == 8.0 && this.toValue == -40.0) {
                target = 4.0;
            } else {
                target = this.fromValue;
            }
            if (y >= -128) {
                double t = (y - -128) / 64.0;
                return Mth.lerp(t, target, this.fromValue);
            }
            return target;
        }

        double val = y;
        boolean bottomFade = this.fromY == -64 && this.toY == -40;
        boolean topFade = this.fromY >= 200 && this.toY >= 200;
        // Only relocate fades for queries OUTSIDE the vanilla band; in-band queries stay vanilla.
        if (bottomFade && minY < -64 && y < -64) {
            int fadeHeight = this.toY - this.fromY;
            if (y < minY) {
                val = this.fromY;
            } else if (y > minY + fadeHeight) {
                val = this.toY;
            } else {
                val = this.fromY + (y - minY);
            }
        } else if (topFade && maxY > 320 && y > 320) {
            int fadeHeight = this.toY - this.fromY;
            int startFadeY = maxY - fadeHeight;
            if (y < startFadeY) {
                val = this.fromY;
            } else if (y > maxY) {
                val = this.toY;
            } else {
                val = this.fromY + (y - startFadeY);
            }
        }
        return vanilla(val);
    }

    @org.spongepowered.asm.mixin.Unique
    private double vanilla(double y) {
        if (y < this.fromY) {
            return this.fromValue;
        } else if (y > this.toY) {
            return this.toValue;
        }
        double t = (double) (y - this.fromY) / (this.toY - this.fromY);
        return Mth.lerp(t, this.fromValue, this.toValue);
    }
}
