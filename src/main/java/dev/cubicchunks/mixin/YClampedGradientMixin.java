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
            // Vanilla clamps both of these below -64, which is what makes everything under the band
            // dead solid. Rather than replacing the gradient with a constant, carry it down to the
            // value vanilla itself reaches at the bottom of its world: near the band the value that
            // matches the band's own cave density, then descending smoothly to that floor value at
            // the configured floor. Caves thin out with depth the way vanilla's do towards y=-64,
            // nothing repeats, and no new formula is introduced - only where the existing gradient
            // starts and ends.
            boolean mainDensity = this.fromValue == 1.5 && this.toValue == -1.5;
            boolean spaghetti = this.fromValue == 8.0 && this.toValue == -40.0;
            if (!mainDensity && !spaghetti) {
                return this.fromValue;
            }
            double near = mainDensity ? 0.8 : 4.0;
            double atFloor = mainDensity ? 1.5 : 8.0;
            if (y >= -128) {
                double t = (y + 128) / 64.0;
                return Mth.lerp(t, near, this.fromValue);
            }
            double graded = descend(y, near, atFloor, minY);
            return mainDensity ? deepBaseline(graded, context, y) : graded;
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

    /**
     * A constant deep baseline means the same density of voids at every depth, which is its own kind
     * of boring. This modulates the baseline - the amount of terrain the noise has to carve through -
     * over an aperiodic pattern in all three axes, so some places and depths are cavernous and others
     * dense, and the pattern never closes up inside a reachable world.
     *
     * The periods are deliberately incommensurate (419, 613, 768, 977 blocks): no common multiple
     * fits within any depth anyone will actually generate. The modulation fades in below the seam, so
     * the band's own caves - and the match between y=-64 and the first stratum - are untouched.
     */
    /** Linear descent from the seam value to the value vanilla uses at the bottom of its world. */
    @org.spongepowered.asm.mixin.Unique
    private static double descend(int y, double nearValue, double floorValue, int minY) {
        if (y >= -128) {
            return nearValue;
        }
        if (minY >= -128) {
            return floorValue;
        }
        double t = Math.min(1.0, (-128.0 - y) / (-128.0 - minY));
        return Mth.lerp(t, nearValue, floorValue);
    }

    @org.spongepowered.asm.mixin.Unique
    private double deepBaseline(double target, DensityFunction.FunctionContext context, int y) {
        double amplitude = CubicConfig.DEEP_CAVE_VARIATION.get();
        if (amplitude <= 0.0) {
            return target;
        }
        // Measured, air fraction is steeply sensitive to this baseline: a target of 0.8 gave about
        // 37 voids per 1k in the first stratum, 0.69 gave about 178, and one stratum that landed
        // near 0.95 came out almost solid at 7.5. So the swing has to stay small or whole slices
        // turn into either sponge or rubble. It also starts well below the band so the match between
        // y=-64 and the stratum under it is left alone.
        double envelope = Math.min(1.0, (-400.0 - y) / 900.0);
        if (envelope <= 0.0) {
            return target;
        }
        // Weighted toward the horizontal terms on purpose: variation that is mostly a function of Y
        // reads as alternating rings, which is just repetition with extra steps. The depth term has
        // a period longer than any reachable stratum so it drifts instead of cycling.
        double pattern = 0.45 * Math.sin(context.blockX() * (2.0 * Math.PI / 419.0))
                + 0.45 * Math.sin(context.blockZ() * (2.0 * Math.PI / 613.0))
                + 0.25 * Math.sin(y * (2.0 * Math.PI / 2999.0))
                + 0.25 * Math.sin((context.blockX() + context.blockZ()) * (2.0 * Math.PI / 977.0));
        return target + amplitude * envelope * pattern;
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
