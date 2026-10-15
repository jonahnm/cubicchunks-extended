package dev.cubicchunks.mixin.client;

import dev.cubicchunks.CubicChunks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Debug/diagnostic: logs once, at the first received chunk packet, which Y bounds the client
 * level is using. This catches sync-order regressions (payload arriving after chunk data).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @org.spongepowered.asm.mixin.Unique
    private static volatile boolean cubicchunks$logged;

    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
    private void cubicchunks$logFirstChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (!cubicchunks$logged && Minecraft.getInstance().level != null) {
            cubicchunks$logged = true;
            var level = Minecraft.getInstance().level;
            CubicChunks.LOGGER.info(
                    "[CubicChunks] first client chunk: dim={} bounds=[{}, {}) sections={}",
                    level.dimension().location(),
                    level.getMinBuildHeight(), level.getMaxBuildHeight(),
                    level.getSectionsCount());
        }
    }
}
