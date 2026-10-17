package dev.cubicchunks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.logging.LogUtils;
import dev.cubicchunks.command.CubicCommands;
import dev.cubicchunks.core.ExtensionCubeManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

/**
 * Entry point of Cubic Chunks Extended.
 *
 * <p>Keeps the vanilla chunk pipeline intact (columns generate and persist exactly as in vanilla,
 * so all modded worldgen applies), patches the vertical bounds of configured dimensions, and
 * streams 16x16x16 extension cubes below the vanilla band around each player.
 */
@Mod(CubicChunks.MOD_ID)
public final class CubicChunks {
    public static final String MOD_ID = "cubicchunks";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<ResourceKey<Level>, ExtensionCubeManager> MANAGERS = new HashMap<>();

    public CubicChunks(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, CubicConfig.SPEC);
        modBus.addListener(this::registerPayloadHandlers);
        if (FMLEnvironment.dist.isClient()) {
            NeoForge.EVENT_BUS.addListener(dev.cubicchunks.net.CubicSyncClientHandler::onClientLogout);
        }
        NeoForge.EVENT_BUS.addListener(this::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(this::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(this::onPlayerRespawn);
        NeoForge.EVENT_BUS.addListener(this::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onChunkDataSave);
        NeoForge.EVENT_BUS.addListener(this::onChunkDataLoad);
        LOGGER.info("Cubic Chunks Extended initialised");
    }

    // ---------------------------------------------------------------- wiring

    private void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(dev.cubicchunks.net.CubicSyncPayload.TYPE,
                        dev.cubicchunks.net.CubicSyncPayload.STREAM_CODEC,
                        (payload, context) -> dev.cubicchunks.net.CubicSyncState.applyFromNetwork(payload.dimensions()));
    }

    private void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        CubicConfig.CubicLevelState state = CubicConfig.stateFor(serverLevel.dimension());
        if (state == null) {
            return;
        }
        dev.cubicchunks.net.CubicSyncState.setServerState(
                parseDimensions(), state.minY(), state.maxY());
        MANAGERS.computeIfAbsent(serverLevel.dimension(),
                key -> new ExtensionCubeManager(serverLevel, state));
    }

    private void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            MANAGERS.remove(serverLevel.dimension());
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        for (ExtensionCubeManager manager : MANAGERS.values()) {
            manager.serverTick();
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CubicCommands.register(event.getDispatcher());
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        var snapshot = dev.cubicchunks.net.CubicSyncState.snapshot();
        if (!snapshot.isEmpty()) {
            PacketDistributor.sendToPlayer(player, new dev.cubicchunks.net.CubicSyncPayload(snapshot));
        }
        if (CubicConfig.DEBUG_DEPTH_TEST.get()) {
            ExtensionCubeManager mgr = MANAGERS.get(player.level().dimension());
            if (mgr != null) {
                debugDepthTest(player, mgr);
            }
        }
    }

    private void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var snapshot = dev.cubicchunks.net.CubicSyncState.snapshot();
            if (!snapshot.isEmpty()) {
                PacketDistributor.sendToPlayer(player, new dev.cubicchunks.net.CubicSyncPayload(snapshot));
            }
        }
    }

    private void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var snapshot = dev.cubicchunks.net.CubicSyncState.snapshot();
            if (!snapshot.isEmpty()) {
                PacketDistributor.sendToPlayer(player, new dev.cubicchunks.net.CubicSyncPayload(snapshot));
            }
        }
    }

    /**
     * Sent during the login configuration phase (before any chunk packets), so clients always
     * know the cubic bounds before decoding section data.
     */
    private void onDatapackSync(OnDatapackSyncEvent event) {
        var snapshot = dev.cubicchunks.net.CubicSyncState.snapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        var payload = new dev.cubicchunks.net.CubicSyncPayload(snapshot);
        event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }

    /**
     * Debug: teleport the joining player below the vanilla band, letting the streaming system
     * generate extension cubes, then log the result a few seconds later.
     */
    private void debugDepthTest(ServerPlayer player, ExtensionCubeManager mgr) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerLevel sl = mgr.level();
        int targetY = Math.max(mgr.state().minY() + 32, mgr.generator().bandMinY() - 96);
        server.tell(new TickTask(server.getTickCount() + 30, () -> {
            player.teleportTo(sl, player.getX(), targetY, player.getZ(), player.getYRot(), player.getXRot());
            player.sendSystemMessage(Component.literal("[CubicChunks] depth test: teleported to y=" + targetY));
            // schedule the result message relative to NOW (cubes stream in over its window)
            server.tell(new TickTask(server.getTickCount() + 300, () -> {
                int cubes = mgr.generatedCount();
                LOGGER.info("[CubicChunks] DEPTH TEST: {} extension cubes generated around player in {}",
                        cubes, sl.dimension().location());
                player.sendSystemMessage(Component.literal(
                        "[CubicChunks] depth test: generated " + cubes + " extension cubes"));
                try {
                    mgr.debugReportCubeComposition(new net.minecraft.world.level.ChunkPos(player.blockPosition()));
                } catch (Throwable t) {
                    LOGGER.warn("[CubicChunks] composition report failed", t);
                }
            }));
        }));
    }

    private void onServerStarted(ServerStartedEvent event) {
        CubicConfig.CubicLevelState state = CubicConfig.stateUnchecked();
        if (state != null) {
            dev.cubicchunks.net.CubicSyncState.setServerState(
                    parseDimensions(), state.minY(), state.maxY());
        }
        int pregen = CubicConfig.DEBUG_PREGEN_SECTIONS.get();
        if (pregen > 0) {
            int radius = CubicConfig.DEBUG_PREGEN_RADIUS.get();
            for (ExtensionCubeManager manager : MANAGERS.values()) {
                manager.debugPregenAroundSpawn(pregen, radius);
            }
        }
    }

    /** Persists which extension cubes this mod generated, per column. */
    private void onChunkDataSave(ChunkDataEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)
                || !(MANAGERS.get(serverLevel.dimension()) instanceof ExtensionCubeManager manager)) {
            return;
        }
        long[] bits = manager.generatedBitsetFor(event.getChunk().getPos());
        if (bits != null) {
            event.getData().putLongArray("CubicGeneratedSections", bits);
        }
    }

    /** Restores the generated-marker (with a composition heuristic for older worlds). */
    private void onChunkDataLoad(ChunkDataEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)
                || !(MANAGERS.get(serverLevel.dimension()) instanceof ExtensionCubeManager manager)) {
            return;
        }
        long[] bits = event.getData().getLongArray("CubicGeneratedSections");
        var pos = event.getChunk().getPos();
        if (bits.length > 0) {
            manager.applyGeneratedBitset(pos, bits);
        } else if (event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk levelChunk) {
            manager.markLikelyGeneratedFromChunk(pos, levelChunk);
        }
    }

    private static List<ResourceLocation> parseDimensions() {
        return CubicConfig.ENABLED_DIMENSIONS.get().stream()
                .map(ResourceLocation::parse)
                .toList();
    }

    /** Used by commands. May be {@code null} when the dimension is not cubic. */
    public static ExtensionCubeManager managerFor(Level level) {
        return MANAGERS.get(level.dimension());
    }
}
