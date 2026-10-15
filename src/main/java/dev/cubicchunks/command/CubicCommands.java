package dev.cubicchunks.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.cubicchunks.CubicChunks;
import dev.cubicchunks.CubicConfig;
import dev.cubicchunks.core.ExtensionCubeManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
                .then(Commands.literal("bounds")
                        .executes(ctx -> bounds(ctx.getSource()))));
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

    private static int teleport(CommandSourceStack source, int y) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        int clamped = Math.max(level.getMinBuildHeight() + 2, Math.min(level.getMaxBuildHeight() - 2, y));
        player.teleportTo(level, player.getX(), clamped, player.getZ(), player.getYRot(), player.getXRot());
        source.sendSuccess(() -> Component.literal("Teleported to y=" + clamped), false);
        return 1;
    }
}
