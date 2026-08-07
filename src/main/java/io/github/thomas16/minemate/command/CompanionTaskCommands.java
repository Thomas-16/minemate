package io.github.thomas16.minemate.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.player2.playerengine.TaskCatalogue;

import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.task.Goal;
import io.github.thomas16.minemate.tool.ToolCall;
import io.github.thomas16.minemate.tool.ToolRegistry;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.Collection;
import java.util.List;

/**
 * Attaches the temporary hardcoded task verbs to /companion.
 *
 * <p>Brigadier still owns argument parsing and tab completion, but every verb now re-encodes its
 * resolved arguments into a {@link ToolCall} and goes through {@link ToolRegistry}. That is
 * deliberate duplication of effort: it makes the typed chat path and the planner path the same
 * path, so a tool cannot behave one way when typed and another way when planned.
 */
final class CompanionTaskCommands {

    private CompanionTaskCommands() {
    }

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext registryAccess) {
        root.then(Commands.literal("follow")
                .executes(ctx -> follow(ctx.getSource(), null))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> follow(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))));
        root.then(resourceCommand("mine"));
        root.then(resourceCommand("fetch"));
        root.then(itemCommand("craft", registryAccess));
        root.then(itemCommand("smelt", registryAccess));
        root.then(storageCommand("deposit", registryAccess));
        root.then(storageCommand("withdraw", registryAccess));
        root.then(Commands.literal("come")
                .executes(ctx -> runTool(ctx.getSource(), ToolCall.of("come_to_owner")))
                .then(Commands.argument("distance", DoubleArgumentType.doubleArg(1.0, 32.0))
                        .executes(ctx -> runTool(ctx.getSource(), ToolCall.of("come_to_owner")
                                .with("distance", DoubleArgumentType.getDouble(ctx, "distance"))))));
        root.then(Commands.literal("goto")
                .then(Commands.argument("pos", ColumnPosArgument.columnPos())
                        .executes(ctx -> goTo(ctx.getSource(), ColumnPosArgument.getColumnPos(ctx, "pos")))
                        .then(Commands.argument("y",
                                IntegerArgumentType.integer(DimensionType.MIN_Y, DimensionType.MAX_Y))
                                .executes(ctx -> goTo(ctx.getSource(),
                                        ColumnPosArgument.getColumnPos(ctx, "pos"),
                                        IntegerArgumentType.getInteger(ctx, "y"))))));
        root.then(Commands.literal("kill")
                .then(Commands.argument("target", EntityArgument.entity())
                        .executes(ctx -> kill(ctx.getSource(), EntityArgument.getEntity(ctx, "target")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> itemCommand(String tool,
            CommandBuildContext registryAccess) {
        RequiredArgumentBuilder<CommandSourceStack, ?> item = Commands.argument("item",
                ItemArgument.item(registryAccess))
                .executes(ctx -> runTool(ctx.getSource(), ToolCall.of(tool)
                        .with("item", ItemArgument.getItem(ctx, "item").getItem())));
        item.then(Commands.argument("count", IntegerArgumentType.integer(1, 2304))
                .executes(ctx -> runTool(ctx.getSource(), ToolCall.of(tool)
                        .with("item", ItemArgument.getItem(ctx, "item").getItem())
                        .with("count", IntegerArgumentType.getInteger(ctx, "count")))));
        return Commands.literal(tool).then(item);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> storageCommand(String tool,
            CommandBuildContext registryAccess) {
        RequiredArgumentBuilder<CommandSourceStack, ?> container = Commands.argument("container",
                BlockPosArgument.blockPos());
        RequiredArgumentBuilder<CommandSourceStack, ?> item = Commands.argument("item",
                ItemArgument.item(registryAccess))
                .executes(ctx -> runTool(ctx.getSource(), ToolCall.of(tool)
                        .with("container", BlockPosArgument.getLoadedBlockPos(ctx, "container"))
                        .with("item", ItemArgument.getItem(ctx, "item").getItem())));
        item.then(Commands.argument("count", IntegerArgumentType.integer(1, 2304))
                .executes(ctx -> runTool(ctx.getSource(), ToolCall.of(tool)
                        .with("container", BlockPosArgument.getLoadedBlockPos(ctx, "container"))
                        .with("item", ItemArgument.getItem(ctx, "item").getItem())
                        .with("count", IntegerArgumentType.getInteger(ctx, "count")))));
        container.then(item);
        return Commands.literal(tool).then(container);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> resourceCommand(String tool) {
        RequiredArgumentBuilder<CommandSourceStack, String> resource = Commands
                .argument("resource", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ResourceNames.values(), builder))
                .executes(ctx -> runTool(ctx.getSource(), ToolCall.of(tool)
                        .with("resource", StringArgumentType.getString(ctx, "resource"))));
        resource.then(Commands.argument("count", IntegerArgumentType.integer(1, 2304))
                .executes(ctx -> runTool(ctx.getSource(), ToolCall.of(tool)
                        .with("resource", StringArgumentType.getString(ctx, "resource"))
                        .with("count", IntegerArgumentType.getInteger(ctx, "count")))));
        return Commands.literal(tool).then(resource);
    }

    /**
     * The sender's name is filled in explicitly so a bare {@code /companion follow} keeps following
     * whoever typed it. The tool's owner fallback then only applies to planner-issued calls.
     */
    private static int follow(CommandSourceStack source, ServerPlayer requestedTarget) {
        ServerPlayer sender = CompanionCommands.requirePlayer(source);
        if (sender == null) {
            return 0;
        }
        ServerPlayer target = requestedTarget == null ? sender : requestedTarget;
        return runTool(source, ToolCall.of("follow").with("player", target.getGameProfile().getName()));
    }

    private static int goTo(CommandSourceStack source, ColumnPos position) {
        return runTool(source, ToolCall.of("goto").with("x", position.x()).with("z", position.z()));
    }

    private static int goTo(CommandSourceStack source, ColumnPos position, int y) {
        return runTool(source, ToolCall.of("goto")
                .with("x", position.x()).with("y", y).with("z", position.z()));
    }

    /**
     * Authorization guards stay here — they are about who may be targeted from chat, not about the
     * call's shape. The tool's own self/owner guard still protects planner-issued calls.
     */
    private static int kill(CommandSourceStack source, Entity target) {
        ServerPlayer sender = CompanionCommands.requirePlayer(source);
        if (sender == null) {
            return 0;
        }
        CompanionEntity companion = CompanionCommands.findCompanion(sender);
        if (companion == null) {
            CompanionCommands.sendNoCompanion(source, sender);
            return 0;
        }
        if (target == sender || target == companion) {
            source.sendFailure(Component.literal("The companion cannot attack that target"));
            return 0;
        }
        if (!(target.level() instanceof ServerLevel)) {
            source.sendFailure(Component.literal("Target is not in a server world"));
            return 0;
        }
        return runTool(source, ToolCall.of("kill").with("target", target.getUUID()));
    }

    private static int runTool(CommandSourceStack source, ToolCall call) {
        ServerPlayer player = CompanionCommands.requirePlayer(source);
        if (player == null) {
            return 0;
        }
        CompanionEntity companion = CompanionCommands.findCompanion(player);
        if (companion == null) {
            CompanionCommands.sendNoCompanion(source, player);
            return 0;
        }
        Goal goal = CompanionCommands.buildTool(source, companion, call);
        if (goal == null) {
            return 0;
        }
        companion.getSupervisor().replace(goal);
        source.sendSuccess(() -> Component.literal("Ordered companion to " + goal.description()), false);
        return 1;
    }

    /** Defers TaskCatalogue initialization until Brigadier actually requests suggestions. */
    private static final class ResourceNames {
        private static final Collection<String> VALUES = List.copyOf(TaskCatalogue.resourceNames());

        private ResourceNames() {
        }

        private static Collection<String> values() {
            return VALUES;
        }
    }
}
