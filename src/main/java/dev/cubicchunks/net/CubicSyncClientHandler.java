package dev.cubicchunks.net;

import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Client-only: clears synced cubic bounds when leaving a server (so bounds don't leak across). */
public final class CubicSyncClientHandler {
    private CubicSyncClientHandler() {}

    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        CubicSyncState.applyFromNetwork(java.util.Map.of());
    }
}
