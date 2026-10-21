package dev.cubicchunks.worldgen;

import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Climate;

/**
 * Vertical biomes for the world below the vanilla band, produced by continuing the band's own
 * climate field rather than inventing one.
 *
 * <p>Vanilla chooses a biome by matching a climate sample - six values its own density functions
 * compute at a position: temperature, humidity, continentalness, erosion, depth, weirdness -
 * against every biome's parameter region, and taking the closest match. Every biome in the
 * dimension takes part in that contest, including the ones other mods add: it is how Mythic
 * Upgrades' mythic caves, barrens and rifts appear in the band at all.
 *
 * <p>The <i>depth</i> field, the one that makes a sample read as underground rather than open
 * air, is built from the dimension's height range and stops changing once you pass the bottom of
 * it. Below the band the raw samples therefore all land in the same neighbourhood, and in practice
 * one cave biome fills the entire modded range.
 *
 * <p>An earlier version of this class answered that by emitting the exact parameter point of a
 * hard-coded vanilla cave biome: a sample sitting on a target point has distance zero to that
 * entry, so it was selected unambiguously - and it also made every other biome in the dimension
 * unable to win, modded ones included. Biomes that carry the features a player is looking for
 * (Mythic Upgrades' gem ores live in its own biomes, not under {@code #minecraft:is_overworld})
 * could not appear below the band at all. The hand-written points could also resolve to surface
 * biomes, because a point is not required to sit inside the region the biome it names actually
 * asks for - a descent measured with this mod installed reported snowy plains and snowy beaches
 * several hundred blocks down.
 *
 * <p>So this maps the <i>coordinate</i> instead of the answer. A position below the band is
 * sampled at a virtual Y inside the band's own cave layer, walking that layer as the real depth
 * increases and running the walk backwards on the next pass, so the deep world cycles through the
 * same underground parameter field the band has. What comes back is vanilla's sample, computed by
 * vanilla's functions at a real band coordinate, and vanilla's picker resolves it against the real
 * biome list. Modded biomes are then selected here exactly as they are above - where their own
 * parameter regions fit - and nothing in this class decides which biomes are allowed.
 */
public final class DeepBiomeProfile {

    private DeepBiomeProfile() {}

    /**
     * Height of the band stretch the walk covers, as a fraction of the band. The band's cave
     * biomes live in its lowest stretch; walking further reaches the surface, where the parameters
     * select surface biomes and the deep world ends up with plains and oceans underground.
     */
    /** Depth axis below the band: just under it, and at the floor of the modded range. */
    private static final long DEPTH_NEAR_BAND = 5500L;
    private static final long DEPTH_FLOOR = 11000L;

    /** Blocks of real depth for one round trip of each swept axis. Different periods so the
     * combination of axes - and therefore the biome selected - does not repeat at a common stride. */
    private static final int WEIRD_PERIOD = 768;
    private static final int EROSION_PERIOD = 1153;
    private static final int HUMID_PERIOD = 1409;

    private static final int CAVE_LAYER_DIVISOR = 6;
    private static final int MIN_CAVE_LAYER = 32;
    private static final int MAX_CAVE_LAYER = 96;

    /**
     * Returns the climate sample for the mapped coordinate, or null when the position is inside the
     * band, where vanilla's own field is correct and is left alone.
     *
     * @param sampler    the climate sampler whose functions are asked for the mapped coordinate
     * @param quartX     quart (section) X of the real position
     * @param quartY     quart Y of the real position
     * @param quartZ     quart Z of the real position
     * @param bandMinY   bottom of the vanilla band, i.e. the top of the modded range
     * @param bandHeight height of the vanilla band, in blocks
     * @param worldMinY  bottom of the modded range
     */
    public static Climate.TargetPoint sample(Climate.Sampler sampler, int quartX, int quartY, int quartZ,
            int bandMinY, int bandHeight, int worldMinY) {
        int blockY = QuartPos.toBlock(quartY);
        if (blockY >= bandMinY) {
            return null;
        }
        int layer = Mth.clamp(bandHeight / CAVE_LAYER_DIVISOR, MIN_CAVE_LAYER, MAX_CAVE_LAYER);
        // One walk through the band's cave layer per band-height of real depth, so the deep world's
        // biomes change at about the rate the band's own do.
        int cycle = Math.max(layer * 2, bandHeight);
        int belowBand = bandMinY - blockY;
        int lap = Math.floorDiv(belowBand, cycle);
        int progress = Math.floorMod(belowBand, cycle);
        int step = (int)((long) progress * layer / cycle);
        // The first walk starts at the band's own floor - the parameters vanilla has at the bottom
        // of the band, which is what the position just below it has to continue - and climbs
        // through the cave layer. Every following walk runs backwards, so the end of one meets the
        // start of the next and the biome field carries no seam in it.
        int offset = (lap & 1) == 0 ? step : layer - 1 - step;
        Climate.TargetPoint sampled = sampler.sample(quartX, QuartPos.fromBlock(bandMinY + offset), quartZ);
        // Weirdness is vanilla's cave-variety axis: it is what separates lush caves from dripstone
        // caves from the deep dark at one and the same depth. Sweeping it with the real depth walks
        // vanilla's picker across those regions as you descend, which is what makes biomes change
        // vertically instead of one regional biome continuing to the floor. Biomes other mods add
        // are gated on these same axes, so they rotate in and out with the sweep as well.
        // Depth is the axis vanilla uses to tell underground from surface, and below the band the
        // field stops changing on it, so it is set from the real depth: shallow cave biomes near the
        // band, the deep ones further down. Weirdness is swept on top of that, so at a given depth
        // the picker still walks across the cave regions that differ on that axis - lush, dripstone,
        // deep dark, and whatever a mod adds with its own gates.
        // The cave biomes vanilla can select underground are gated on regional axes, not on depth:
        // dripstone caves want continentalness at 0.8 and above, lush caves want humidity at 0.7 and
        // above, the deep dark wants erosion at -0.375 or below. Sweeping depth therefore cannot
        // produce them - only those axes can. So the field below the band keeps the region's own
        // temperature and continentalness (which is what decides whether a place is land, coast or
        // sea, and stops ocean biomes turning up underground) and blends the three axes the cave
        // entries gate on toward a swept target, each on its own period so their combinations
        // rotate as you descend.
        //
        // Blended, not added: a value already spanning +-10000 saturates the moment 20000 is added
        // to it, which is what the first version of this did - the clamp produced a constant and
        // three different strata reported byte-identical biome counts.
        long weirdness = blend(sampled.weirdness(), triangle(belowBand, WEIRD_PERIOD), 0.85);
        long erosion = blend(sampled.erosion(), triangle(belowBand + 400, EROSION_PERIOD), 0.6);
        long humidity = blend(sampled.humidity(), triangle(belowBand + 900, HUMID_PERIOD), 0.55);
        double fraction = Math.min(1.0, (double) belowBand / Math.max(1, bandMinY - worldMinY));
        long depth = (long) (DEPTH_NEAR_BAND + fraction * (DEPTH_FLOOR - DEPTH_NEAR_BAND));
        return new Climate.TargetPoint(sampled.temperature(), humidity,
                sampled.continentalness(), erosion, depth, weirdness);
    }

    /**
     * A triangular wave in 0..1 over the given period, so a value sweeps out and back again and the
     * boundary between one round trip and the next is continuous.
     */
    private static double triangle(int value, int period) {
        int half = period / 2;
        int phase = Math.floorMod(value, period);
        return phase < half ? (double) phase / half : 1.0 - (double) (phase - half) / half;
    }

    /** Moves a sampled axis a given fraction of the way toward the swept value at phase t. */
    private static long blend(long sampled, double t, double weight) {
        long target = (long) (-10000.0 + 20000.0 * t);
        return (long) (sampled + (target - sampled) * weight);
    }
}