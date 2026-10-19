package dev.cubicchunks.worldgen;

import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Climate;

/**
 * Vertical biomes for the world below the vanilla band.
 *
 * <p>Vanilla picks a biome by matching a climate sample against every biome's target region
 * ({@link Climate.MultiNoise}) and taking the closest match. Below the band the density fields keep
 * extrapolating, so a raw sample lands wherever the extrapolation points: in practice the same cave
 * biome at every depth. Nothing changed as you went down, which is exactly what a vertical world
 * should not do.
 *
 * <p>This profile instead reads how far below the band a position is - 0 at the band, 1 at the
 * bottom of the modded range - and answers with the exact target point of a vanilla cave biome. A
 * sample sitting on a target point has distance 0 to that entry, so vanilla's own picker selects it
 * unambiguously, and everything the biome carries with it (cave decorations, mob spawns, environment
 * attributes, which features run) is vanilla's own data, not something this mod invents.
 *
 * <p>The targets are transcribed from {@code OverworldBiomeBuilder}: the midpoint of each interval
 * named by its {@code addUndergroundBiome}/{@code addBottomBiome} entry, and 0 on the axes an entry
 * leaves open (their interval is the full range, which is centred on 0). Climate coordinates are
 * floats scaled by 10000 - see {@link Climate#quantizeCoord}.
 *
 * <p>Two things keep the result from looking like stacked slabs. Which layer a position is in is
 * shifted by the sample's own fields (they are smooth in x, y and z), so layer borders curve and
 * sit at different depths in different regions; and inside a layer the biome is picked with the same
 * gates vanilla uses for that biome - humidity for lush caves, continentalness for dripstone caves,
 * the erosion/continentalness/weirdness combination for sulfur caves, the deep-dark erosion band for
 * deep dark - so patches line up with the regions above them, the way vanilla cave biomes do. The
 * layer decides which biomes are on offer, so depth is what changes the answer.
 */
public final class DeepBiomeProfile {

    private DeepBiomeProfile() {}

    /**
     * Lush caves: {@code humidity} in [0.7, 1.0], {@code depth} in [0.2, 0.9], everything else open.
     * Dripstone caves: {@code continentalness} in [0.8, 1.0], {@code depth} in [0.2, 0.9].
     *
     * <p>The depth axis is set near the top of the interval those entries allow rather than at its
     * midpoint. On the other axes a cave entry is wide open, so a point at the middle of its ranges
     * is usually *also* inside some surface biome's ranges - and since vanilla adds the surface
     * entries first, a tie goes to the surface biome and the remap silently does nothing. Depth is
     * the axis that separates them, and it is the axis this profile is about, so it is the one to
     * place deliberately. Deep dark is already at the point 1.1 that its own entry demands.
     */
    private static final Climate.TargetPoint LUSH_CAVES =
            new Climate.TargetPoint(0L, 8500L, 0L, 0L, 8900L, 0L);

    private static final Climate.TargetPoint DRIPSTONE_CAVES =
            new Climate.TargetPoint(0L, 0L, 9000L, 0L, 8900L, 0L);

    /** Deep dark: {@code erosion} in [-1.0, -0.375], {@code depth} at the point 1.1. */
    private static final Climate.TargetPoint DEEP_DARK_POINT =
            new Climate.TargetPoint(0L, 0L, 0L, -6875L, 11000L, 0L);

    /**
     * Sulfur caves: {@code continentalness} in [-0.19, 0.55] (coast through inland), {@code erosion}
     * in [0.45, 1.0], {@code weirdness} in [-1.1, -0.85], {@code depth} in [0.2, 0.9].
     */
    private static final Climate.TargetPoint SULFUR_CAVES =
            new Climate.TargetPoint(0L, 0L, 1800L, 7250L, 8900L, -9750L);

    /** Vanilla's own gates for each cave biome, on the quantized scale the sampler works in.
     * Each entry is an axis (0 temperature, 1 humidity, 2 continentalness, 3 erosion, 5 weirdness)
     * and the interval that biome's entry in {@code OverworldBiomeBuilder} constrains it to. */
    private record CaveBiome(Climate.TargetPoint target, long[][] gates) {}

    private static final CaveBiome LUSH = new CaveBiome(LUSH_CAVES, new long[][] {{1, 7000L, 10000L}});
    private static final CaveBiome DRIPSTONE =
            new CaveBiome(DRIPSTONE_CAVES, new long[][] {{2, 8000L, 10000L}});
    private static final CaveBiome DEEP_DARK =
            new CaveBiome(DEEP_DARK_POINT, new long[][] {{3, -10000L, -3750L}});
    private static final CaveBiome SULFUR = new CaveBiome(SULFUR_CAVES, new long[][] {
            {2, -1900L, 5500L}, {3, 4500L, 10000L}, {5, -11000L, -8500L}});

    /**
     * Remaps one biome sample from below the vanilla band onto the profile. Samples inside the band
     * are returned untouched: the band is vanilla's, and the modded range starts where it ends.
     *
     * @param sample    the sample vanilla's climate machinery produced for this position
     * @param blockY    block Y of the sampled position
     * @param bandMinY  top of the modded range, i.e. the vanilla band's own minimum Y
     * @param worldMinY bottom of the modded range
     */
    public static Climate.TargetPoint apply(
            Climate.TargetPoint sample, int blockY, int bandMinY, int worldMinY) {
        if (blockY >= bandMinY) {
            return sample;
        }
        double fraction = (double) (bandMinY - blockY) / (double) Math.max(16, bandMinY - worldMinY);
        // Wobble the layer coordinate with smooth fields of the sample itself: weirdness is the axis
        // vanilla treats as "cave-ness", continentalness is regional, erosion adds a slower tilt.
        double layer = Mth.clamp(
                fraction
                        + 0.16 * unit(sample.weirdness())
                        + 0.10 * unit(sample.continentalness())
                        + 0.05 * unit(sample.erosion()),
                0.0, 1.0);

        if (layer < 0.25) {
            // Just under the band: the cave biomes vanilla already knows there.
            return pick(sample, LUSH, DRIPSTONE);
        }
        if (layer < 0.5) {
            return pick(sample, DRIPSTONE, LUSH, SULFUR);
        }
        if (layer < 0.75) {
            // The deep dark: sculk, no springs, the dark the name is about.
            return pick(sample, DEEP_DARK, DRIPSTONE, SULFUR);
        }
        // The floor: sulfur country, which is where this mod's lava belt is, and dark pockets
        // between them.
        return pick(sample, SULFUR, DEEP_DARK, LUSH);
    }

    /** The candidate whose own target region this sample sits closest to. */
    private static Climate.TargetPoint pick(Climate.TargetPoint sample, CaveBiome... options) {
        CaveBiome best = options[0];
        long bestFitness = fitness(sample, best);
        for (int i = 1; i < options.length; i++) {
            long fitness = fitness(sample, options[i]);
            if (fitness < bestFitness) {
                best = options[i];
                bestFitness = fitness;
            }
        }
        return best.target();
    }

    /**
     * How far a sample is from a cave biome's target region, measured the way vanilla measures it:
     * the squared distance to each interval that biome constrains, summed, and zero on an axis the
     * biome leaves open. Ties go to the first candidate, which is also how vanilla's picker
     * resolves them.
     */
    private static long fitness(Climate.TargetPoint sample, CaveBiome biome) {
        long total = 0L;
        for (long[] gate : biome.gates()) {
            total += gap(axis(sample, (int) gate[0]), gate[1], gate[2]);
        }
        return total;
    }

    private static long axis(Climate.TargetPoint point, int index) {
        return switch (index) {
            case 0 -> point.temperature();
            case 1 -> point.humidity();
            case 2 -> point.continentalness();
            case 3 -> point.erosion();
            case 5 -> point.weirdness();
            default -> point.depth();
        };
    }

    private static long gap(long value, long min, long max) {
        if (value < min) {
            return (min - value) * (min - value);
        }
        if (value > max) {
            return (value - max) * (value - max);
        }
        return 0L;
    }

    private static double unit(long quantized) {
        return Mth.clamp((double) quantized / 10000.0, -1.0, 1.0);
    }
}
