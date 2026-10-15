package dev.cubicchunks.api;

/**
 * Implemented (via mixin) by {@code ServerLevel} and {@code ClientLevel}. Provides the extended
 * cubic bounds of a level, lazily resolved from configuration (server) or the server-sent sync
 * payload (client).
 */
public interface CubicLevelBounds {

    /** {@code true} when this level is a cubic (extended-height) world. */
    boolean cubicchunks$isCubic();

    /**
     * Extended world floor (multiple of 16), valid only when {@link #cubicchunks$isCubic()}.
     */
    default int cubicchunks$minY() {
        return Integer.MIN_VALUE;
    }

    /** Extended world ceiling (multiple of 16), valid only when {@link #cubicchunks$isCubic()}. */
    default int cubicchunks$maxY() {
        return Integer.MIN_VALUE;
    }
}
