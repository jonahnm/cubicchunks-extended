package dev.cubicchunks.worldgen;

/**
 * Thread-scoped flags for the world-generation pipeline.
 *
 * <ul>
 *   <li>{@link #CURRENT_MULTIPLIER}: set while the extension feature planner evaluates placement
 *       modifiers; scales placement counts for each tiling of a feature's native vertical span, so
 *       vanilla features keep a sensible density below the band (anchored ranges themselves are not
 *       moved: ores keep their vanilla Y ranges). Vanilla band decoration never sets it, so the
 *       band stays 100% vanilla.</li>
 *   <li>{@link #REMAP_DEEP_BIOMES}: set while sampling biomes for extension cubes so the climate
 *       sampler mixin maps deep coordinates onto vanilla cave biomes.</li>
 * </ul>
 */
public final class WorldGenState {
    private WorldGenState() {}

    public static final ThreadLocal<Double> CURRENT_MULTIPLIER = ThreadLocal.withInitial(() -> 1.0);
    public static final ThreadLocal<Boolean> REMAP_DEEP_BIOMES = ThreadLocal.withInitial(() -> false);
}
