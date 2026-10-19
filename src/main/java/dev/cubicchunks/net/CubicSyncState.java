package dev.cubicchunks.net;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.resources.Identifier;

/**
 * Shared, lookup-only state describing which dimensions the server has made cubic, along with the
 * extended bounds. Written on the server from config; pushed to clients at login via
 * {@link CubicSyncPayload} (the payload handler writes the same structure client-side).
 */
public final class CubicSyncState {
    private CubicSyncState() {}

    /** Bounds for a cubic dimension. */
    public record Bounds(int minY, int maxY) {}

    private static volatile Map<Identifier, Bounds> dimensions = Map.of();

    /** Server side: called when config is loaded / available. */
    public static void setServerState(Iterable<Identifier> dims, int minY, int maxY) {
        Map<Identifier, Bounds> map = new ConcurrentHashMap<>();
        for (Identifier dim : dims) {
            map.put(dim, new Bounds(minY, maxY));
        }
        dimensions = Map.copyOf(map);
    }

    /** Client side: applied from the sync payload. */
    public static void applyFromNetwork(Map<Identifier, Bounds> map) {
        dimensions = Map.copyOf(map);
        if (!dimensions.isEmpty()) {
            dev.cubicchunks.CubicChunks.LOGGER.info("[CubicChunks] cubic bounds applied: {}", dimensions);
        }
    }

    @Nullable
    public static Bounds get(Identifier dimension) {
        return dimensions.get(dimension);
    }

    public static Map<Identifier, Bounds> snapshot() {
        return dimensions;
    }
}
