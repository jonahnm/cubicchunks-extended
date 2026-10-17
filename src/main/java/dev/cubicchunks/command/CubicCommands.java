package dev.cubicchunks.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.cubicchunks.CubicChunks;
import dev.cubicchunks.CubicConfig;
import dev.cubicchunks.core.ExtensionCubeManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** {@code /cubicchunks ...} debugging and inspection commands. */
public final class CubicCommands {
    private CubicCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cubicchunks")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("tp")
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .executes(ctx -> teleport(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "y")))))
                .then(Commands.literal("verifyall")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 4))
                                .executes(ctx -> verifyAll(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(Commands.literal("verify")
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .executes(ctx -> verify(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "y")))))
                .then(Commands.literal("bounds")
                        .executes(ctx -> bounds(ctx.getSource())))
                .then(Commands.literal("regen")
                        .executes(ctx -> regen(ctx.getSource(), -1))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 64))
                                .executes(ctx -> regen(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius"))))));
    }

    private static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ExtensionCubeManager manager = CubicChunks.managerFor(level);
        if (manager == null) {
            source.sendFailure(Component.literal("Dimension " + level.dimension().location() + " is not cubic-enabled."));
            return 0;
        }
        CubicConfig.CubicLevelState state = manager.state();
        source.sendSuccess(() -> Component.literal(String.format(
                "Cubic dimension %s: range [%d, %d) (%d sections); vanilla band [%d, %d); generated extension cubes: %d",
                level.dimension().location(), state.minY(), state.maxY(), state.sectionsCount(),
                manager.generator().bandMinY(), manager.generator().bandMaxY(),
                manager.generatedCount())), false);
        return 1;
    }

    private static int bounds(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        source.sendSuccess(() -> Component.literal(String.format(
                "Effective Y range in %s: [%d, %d)",
                level.dimension().location(), level.getMinBuildHeight(), level.getMaxBuildHeight())), false);
        return 1;
    }

    /**
     * Rebuilds the terrain of the generated extension cubes without touching what the player put
     * there: the fresh cube is merged over the installed one, so only blocks vanilla generation
     * could have produced are replaced.
     */
    private static int regen(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        dev.cubicchunks.core.ExtensionCubeManager manager = CubicChunks.managerFor(level);
        if (manager == null) {
            source.sendFailure(Component.literal("Dimension " + level.dimension().location()
                    + " is not cubic-enabled."));
            return 0;
        }
        var center = source.getPlayer() != null
                ? source.getPlayer().chunkPosition()
                : new net.minecraft.world.level.ChunkPos(level.getSharedSpawnPos());
        long startNanos = System.nanoTime();
        int rebuilt = manager.regenerateGeneratedCubes(center, radius);
        long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
        source.sendSuccess(() -> Component.literal(String.format(
                "[CubicChunks] regenerated %d extension cubes (%d ms); player changes and structure"
                        + " interiors preserved.", rebuilt, tookMs)), false);
        return 1;
    }
    private static int verifyAll(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        dev.cubicchunks.core.ExtensionCubeManager manager = CubicChunks.managerFor(level);
        if (manager == null) {
            source.sendFailure(Component.literal("Dimension " + level.dimension().location() + " is not cubic-enabled."));
            return 0;
        }
        var center = source.getPlayer() != null
                ? source.getPlayer().chunkPosition()
                : new net.minecraft.world.level.ChunkPos(level.getSharedSpawnPos());
        manager.startVerifyAll(radius, center);
        source.sendSuccess(() -> Component.literal(
                "[CubicChunks] bulk verification started across " + (2 * radius + 1) * (2 * radius + 1)
                        + " columns (one column per tick) — watch the server log."), false);
        return 1;
    }

    private static int verify(CommandSourceStack source, int y) {
        ServerLevel level = source.getLevel();
        dev.cubicchunks.core.ExtensionCubeManager manager = CubicChunks.managerFor(level);
        if (manager == null) {
            source.sendFailure(Component.literal("Dimension " + level.dimension().location() + " is not cubic-enabled."));
            return 0;
        }
        var pos = source.getPlayer() != null
                ? source.getPlayer().chunkPosition()
                : new net.minecraft.world.level.ChunkPos(level.getSharedSpawnPos());
        boolean generated = manager.debugEnsureCube(pos, y);
        var chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) {
            source.sendFailure(Component.literal("Column not loaded yet; move around and retry."));
            return 0;
        }
        String report = manager.generator().verifyAgainstFullColumn(
                chunk, net.minecraft.core.SectionPos.blockToSectionCoord(y));
        source.sendSuccess(() -> Component.literal("[CubicChunks] verify: " + (generated ? "(generated now) " : "") + report), false);
        CubicChunks.LOGGER.info("[CubicChunks] verify: {}", report);
        return 1;
    }

    private static int teleport(CommandSourceStack source, int y) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        int clamped = Math.max(level.getMinBuildHeight() + 2, Math.min(level.getMaxBuildHeight() - 2, y));
        player.teleportTo(level, player.getX(), clamped, player.getZ(), player.getYRot(), player.getXRot());
        source.sendSuccess(() -> Component.literal("Teleported to y=" + clamped), false);
        return 1;
    }
}
