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
                .requires(net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS))
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
                .then(Commands.literal("biomescan")
                        .executes(ctx -> biomescan(ctx.getSource())))
                .then(Commands.literal("bench")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 8))
                                .then(Commands.argument("toY", IntegerArgumentType.integer(-8388592, 320))
                                        .executes(ctx -> bench(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                IntegerArgumentType.getInteger(ctx, "toY"))))))
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
            source.sendFailure(Component.literal("Dimension " + level.dimension().identifier() + " is not cubic-enabled."));
            return 0;
        }
        CubicConfig.CubicLevelState state = manager.state();
        source.sendSuccess(() -> Component.literal(String.format(
                "Cubic dimension %s: range [%d, %d) (%d sections); vanilla band [%d, %d); generated extension cubes: %d",
                level.dimension().identifier(), state.minY(), state.maxY(), state.sectionsCount(),
                manager.generator().bandMinY(), manager.generator().bandMaxY(),
                manager.generatedCount())), false);
        return 1;
    }

    private static int bounds(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        source.sendSuccess(() -> Component.literal(String.format(
                "Effective Y range in %s: [%d, %d)",
                level.dimension().identifier(), level.getMinY(), level.getMaxY() + 1)), false);
        return 1;
    }

    /**
     * Rebuilds the terrain of the generated extension cubes without touching what the player put
     * there: the fresh cube is merged over the installed one, so only blocks vanilla generation
     * could have produced are replaced.
     */
    /** Debug: measure what a straight descent costs at increasing depth (see the log). */
    private static int biomescan(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        dev.cubicchunks.core.ExtensionCubeManager manager = CubicChunks.managerFor(level);
        if (manager == null) {
            source.sendFailure(Component.literal("Dimension " + level.dimension().identifier()
                    + " is not cubic-enabled."));
            return 0;
        }
        var center = source.getPlayer() != null
                ? source.getPlayer().chunkPosition()
                : net.minecraft.world.level.ChunkPos.containing(level.getRespawnData().pos());
        source.sendSuccess(() -> Component.literal("[CubicChunks] biome field scan: 8 chunks out, band"
                + " heights only - see the server log."), false);
        manager.debugScanBiomeField(center, 24, new int[]{-64, -40, -20, -4, 8, 40, 96, 192});
        source.sendSuccess(() -> Component.literal("[CubicChunks] scan finished."), false);
        return 1;
    }

    private static int bench(CommandSourceStack source, int radius, int toY) {
        ServerLevel level = source.getLevel();
        dev.cubicchunks.core.ExtensionCubeManager manager = CubicChunks.managerFor(level);
        if (manager == null) {
            source.sendFailure(Component.literal("Dimension " + level.dimension().identifier()
                    + " is not cubic-enabled."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "[CubicChunks] bench: walking a descent down to y=" + toY
                        + " over a " + (2 * radius + 1) + "x" + (2 * radius + 1) + " column area"
                        + " - watch the server log."), false);
        manager.debugDescentBench(radius, net.minecraft.core.SectionPos.blockToSectionCoord(toY));
        source.sendSuccess(() -> Component.literal("[CubicChunks] bench finished."), false);
        return 1;
    }

    private static int regen(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        dev.cubicchunks.core.ExtensionCubeManager manager = CubicChunks.managerFor(level);
        if (manager == null) {
            source.sendFailure(Component.literal("Dimension " + level.dimension().identifier()
                    + " is not cubic-enabled."));
            return 0;
        }
        var center = source.getPlayer() != null
                ? source.getPlayer().chunkPosition()
                : net.minecraft.world.level.ChunkPos.containing(level.getRespawnData().pos());
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
            source.sendFailure(Component.literal("Dimension " + level.dimension().identifier() + " is not cubic-enabled."));
            return 0;
        }
        var center = source.getPlayer() != null
                ? source.getPlayer().chunkPosition()
                : net.minecraft.world.level.ChunkPos.containing(level.getRespawnData().pos());
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
            source.sendFailure(Component.literal("Dimension " + level.dimension().identifier() + " is not cubic-enabled."));
            return 0;
        }
        var pos = source.getPlayer() != null
                ? source.getPlayer().chunkPosition()
                : net.minecraft.world.level.ChunkPos.containing(level.getRespawnData().pos());
        // 26.x drops idle spawn chunks quickly, so make the column resident first (the same
        // loading path /verifyall uses) before asking the manager for a cube inside it.
        level.getChunk(pos.x(), pos.z(),
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
        boolean generated = manager.debugEnsureCube(pos, y);
        var chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
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
        int clamped = Math.max(level.getMinY() + 2, Math.min(level.getMaxY() - 1, y));
        player.teleportTo(level, player.getX(), clamped, player.getZ(),
                java.util.EnumSet.noneOf(net.minecraft.world.entity.Relative.class), player.getYRot(), player.getXRot(), false);
        source.sendSuccess(() -> Component.literal("Teleported to y=" + clamped), false);
        return 1;
    }
}
