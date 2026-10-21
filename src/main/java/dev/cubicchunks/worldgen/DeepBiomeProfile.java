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
     */
    public static Climate.TargetPoint sample(
            Climate.Sampler sampler, int quartX, int quartY, int quartZ, int bandMinY, int bandHeight) {
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
        return sampler.sample(quartX, QuartPos.fromBlock(bandMinY + offset), quartZ);
    }
}
