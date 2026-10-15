package dev.cubicchunks.net;

import java.util.HashMap;
import java.util.Map;

import dev.cubicchunks.CubicChunks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: tells the client which dimensions are cubic and with what extended bounds, so
 * the client-side Y accessors can be patched to match before chunk data is decoded.
 */
public record CubicSyncPayload(Map<ResourceLocation, CubicSyncState.Bounds> dimensions)
        implements CustomPacketPayload {

    public static final Type<CubicSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CubicChunks.MOD_ID, "sync_bounds"));

    public static final StreamCodec<FriendlyByteBuf, CubicSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.dimensions.size());
                for (Map.Entry<ResourceLocation, CubicSyncState.Bounds> e : payload.dimensions.entrySet()) {
                    buf.writeResourceLocation(e.getKey());
                    buf.writeVarInt(e.getValue().minY());
                    buf.writeVarInt(e.getValue().maxY());
                }
            },
            buf -> {
                int size = buf.readVarInt();
                Map<ResourceLocation, CubicSyncState.Bounds> map = new HashMap<>(size);
                for (int i = 0; i < size; i++) {
                    ResourceLocation dim = buf.readResourceLocation();
                    map.put(dim, new CubicSyncState.Bounds(buf.readVarInt(), buf.readVarInt()));
                }
                return new CubicSyncPayload(map);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
