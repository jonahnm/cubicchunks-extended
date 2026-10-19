package dev.cubicchunks;

import java.util.List;

import net.minecraft.IdentifierException;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common (server-side) configuration for Cubic Chunks Extended.
 *
 * <p>Values are normalized via {@link #stateFor(ResourceKey)} which validates bounds and returns
 * {@code null} for dimensions that are not cube-enabled.
 */
public final class CubicConfig {
    private CubicConfig() {}

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ENABLED_DIMENSIONS;
    public static final ModConfigSpec.IntValue WORLD_MIN_Y;
    public static final ModConfigSpec.IntValue WORLD_MAX_Y;

    public static final ModConfigSpec.IntValue VERTICAL_LOAD_DISTANCE;
    public static final ModConfigSpec.IntValue GENERATION_PER_TICK;
    public static final ModConfigSpec.IntValue STREAM_NEAR_RADIUS;
    public static final ModConfigSpec.IntValue STREAM_FAR_CUBES;

    public static final ModConfigSpec.BooleanValue DEEP_BEDROCK_FLOOR;
    public static final ModConfigSpec.BooleanValue REMAP_DEEP_BIOMES;
    public static final ModConfigSpec.IntValue LAVA_NEAR_FLOOR_DEPTH;
    public static final ModConfigSpec.DoubleValue ORE_DEPTH_BONUS;
    public static final ModConfigSpec.BooleanValue DEEP_STRUCTURES;

    public static final ModConfigSpec.IntValue DEBUG_PREGEN_SECTIONS;
    public static final ModConfigSpec.IntValue DEBUG_PREGEN_RADIUS;
    public static final ModConfigSpec.BooleanValue DEBUG_DEPTH_TEST;

    /** Hard sanity cap: |Y| must stay within this many blocks (SectionPos packing is safe at±2^23). */
    public static final int ABSOLUTE_Y_LIMIT = SectionPosLimits.BLOCK_LIMIT;

    /** Utility constants for coordinate packing limits. */
    public static final class SectionPosLimits {
        /** SectionPos packs y in 20 bits -> sections in [-2^19, 2^19). */
        static final int SECTION_LIMIT = 1 << 19;
        public static final int BLOCK_LIMIT = (SECTION_LIMIT - 1) << 4; // ~8.3M blocks
        private SectionPosLimits() {}
    }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment(
                "Dimensions that get the extended cubic world height. Any dimension id is allowed",
                "(vanilla: minecraft:overworld, minecraft:the_nether, minecraft:the_end; also modded",
                "dimensions). The vanilla noise band of the dimension keeps 100% vanilla (+modded)",
                "generation; everything beyond it is streamed in as extended 16x16x16 cubes.")
            .push("general");
        ENABLED_DIMENSIONS = b.defineList(
                "enabledDimensions",
                List.of("minecraft:overworld"),
                () -> "minecraft:overworld",
                o -> o instanceof String s && isValidDimId(s));
        b.pop();

        b.comment(
                "World bounds for enabled dimensions. Both are snapped outside to multiples of 16.",
                "Vanilla band stays untouched (-64..384 for the overworld); cubic generation fills the",
                "rest. Practical memory concern: each loaded column allocates (maxY-minY)/16 section",
                "objects; a range of ~3072 costs per-column about what vanilla costs at its 384. Keep",
                "|Y| below " + ABSOLUTE_Y_LIMIT + ".")
            .push("world");
        WORLD_MIN_Y = b.defineInRange("minY", -2048, -SectionPosLimits.BLOCK_LIMIT, 320);
        WORLD_MAX_Y = b.defineInRange("maxY", 1024, -256, SectionPosLimits.BLOCK_LIMIT);
        b.pop();

        b.comment(
                "How far (in 16-block cubes, vertically) around each player's eye the extension cubes",
                "are generated/loaded. Per-player horizontal radius follows the server's view distance.")
            .push("streaming");
        VERTICAL_LOAD_DISTANCE = b.defineInRange("verticalLoadDistance", 5, 1, 64);
        GENERATION_PER_TICK = b.defineInRange("generationBudgetPerTick", 2, 1, 64);
        b.comment("Columns within streamNearRadius of a player are streamed with the full vertical",
                "window (verticalLoadDistance below the eye plus a digging margin). Columns further",
                "out keep only streamFarCubes above and below the eye: what a player can see at the",
                "edge of their view is the layer they are in, and every extra cube there costs a",
                "whole-column save and a whole-column chunk packet, both of which get more expensive",
                "the deeper that column has been filled.");
        STREAM_NEAR_RADIUS = b.defineInRange("streamNearRadius", 3, 1, 32);
        STREAM_FAR_CUBES = b.defineInRange("streamFarCubes", 2, 1, 32);
        b.pop();

        b.comment(
                "The deep world uses vanilla's own noise machinery extended downward (density caves,",
                "aquifers, vanilla features with depth-scaled anchors). No custom terrain is added.")
            .push("generation");
        b.comment("Place a bedrock floor at the world bottom (deepBedrockFloorY); when disabled no",
                "bedrock exists anywhere, including the vanilla band floor.");
        DEEP_BEDROCK_FLOOR = b.define("deepBedrockFloor", true);
        b.comment("Map biomes below the vanilla band onto vanilla cave biomes (otherwise climate",
                "extrapolation below the band produces nonsense biomes).");
        REMAP_DEEP_BIOMES = b.define("deepBiomeRemap", true);
        b.comment("No lava below y=-54; lava exists only within this many blocks above the world",
                "floor (worldMinY). Set 0 for no deep lava at all.");
        LAVA_NEAR_FLOOR_DEPTH = b.defineInRange("lavaNearFloorDepth", 32, 0, 1000000);
        b.comment("Ore probability multiplier increase per ore-range tiling below the vanilla",
                "band. Ores continue naturally below -64 (vanilla ranges already dip below it),",
                "then tile downward; each tiling multiplies placement count by 1 + n * this.");
        ORE_DEPTH_BONUS = b.defineInRange("oreDepthBonusPerStratum", 0.5, 0.0, 16.0);
        b.comment("Generate vanilla structures (ancient cities, villages, ...) in every stratum",
                "below the vanilla band: each structure's pieces are translated down per stratum,",
                "with vanilla terrain adaptation (beard) applied by the noise generator.");
        DEEP_STRUCTURES = b.define("deepStructures", true);
        b.pop();

        b.comment("Development aids; not for release worlds.").push("debug");
        b.comment("If >= 0, synchronously generate this many extension sections below spawn at start.");
        DEBUG_PREGEN_SECTIONS = b.defineInRange("startupPregenSections", -1, -1, 4096);
        b.comment("Pregen ring radius (columns) around spawn for startupPregenSections.");
        DEBUG_PREGEN_RADIUS = b.defineInRange("startupPregenRadius", 0, 0, 64);
        b.comment("If true, on player join the server teleports them to y=-96 in a cubic dimension",
                "and logs how many extension cubes streamed in (end-to-end streaming test).");
        DEBUG_DEPTH_TEST = b.define("autoDepthTest", false);
        b.pop();

        SPEC = b.build();
    }

    private static boolean isValidDimId(String s) {
        try {
            Identifier.parse(s);
            return true;
        } catch (IdentifierException e) {
            return false;
        }
    }

    /**
     * Returns the normalized cubic state for a dimension, or {@code null} when the dimension is not
     * cube-enabled or the configured bounds are invalid.
     */
    public static CubicLevelState stateFor(ResourceKey<Level> dimension) {
        List<? extends String> list = ENABLED_DIMENSIONS.get();
        if (list == null || !list.contains(dimension.identifier().toString())) {
            return null;
        }
        return stateUnchecked();
    }

    /** Builds the shared state object from the current config values (validating). */
    static CubicLevelState stateUnchecked() {
        int rawMin = WORLD_MIN_Y.get();
        int rawMax = WORLD_MAX_Y.get();
        // Snap outward to multiples of 16 (cube boundaries).
        int minY = Math.floorDiv(rawMin, 16) * 16;
        int maxY = (Math.floorDiv(rawMax, 16) + 1) * 16;
        minY = Math.max(-SectionPosLimits.BLOCK_LIMIT, minY);
        maxY = Math.min(SectionPosLimits.BLOCK_LIMIT, maxY);
        if (maxY - minY < 512) {
            CubicChunks.LOGGER.warn(
                    "[CubicChunks] configured world height ({}..{}) is too small, ignoring cubic range",
                    rawMin, rawMax);
            return null;
        }
        return new CubicLevelState(minY, maxY);
    }

    /**
     * Cached extension bounds for the hot worldgen mixins (gradient re-baselining, anchor scaling,
     * aquifer adjustments). {@code null} when no cubic dimensions are enabled or bounds invalid.
     * Cached because these are queried per block during noise evaluation.
     */
    private static volatile int[] cachedExtension = null;

    private static int[] extensionCache() {
        int[] c = cachedExtension;
        if (c == null) {
            List<? extends String> dims = ENABLED_DIMENSIONS.get();
            CubicLevelState s = (dims == null || dims.isEmpty()) ? null : stateUnchecked();
            c = s == null ? new int[0] : new int[] {s.minY(), s.maxY()};
            cachedExtension = c;
        }
        return c.length == 0 ? null : c;
    }

    /** Extension floor for overworld-pattern worldgen, or {@code null} when not configured. */
    public static Integer extensionMinY() {
        int[] c = extensionCache();
        return c == null ? null : c[0];
    }

    /** Extension ceiling for overworld-pattern worldgen, or {@code null} when not configured. */
    public static Integer extensionMaxY() {
        int[] c = extensionCache();
        return c == null ? null : c[1];
    }

    /** Clears the cached bounds (config reload). */
    public static void invalidateExtensionCache() {
        cachedExtension = null;
    }

    public static final class CubicLevelState {
        private final int minY;
        private final int maxY;

        CubicLevelState(int minY, int maxY) {
            this.minY = minY;
            this.maxY = maxY;
            assert (minY & 15) == 0 && (maxY & 15) == 0 && maxY - minY >= 512;
        }

        public int minY() { return minY; }
        public int maxY() { return maxY; }
        public int minSection() { return minY >> 4; }
        public int maxSectionExclusive() { return maxY >> 4; }
        public int sectionsCount() { return maxSectionExclusive() - minSection(); }
    }
}
