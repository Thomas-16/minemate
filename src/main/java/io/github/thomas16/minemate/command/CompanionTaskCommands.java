package io.github.thomas16.minemate.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.player2.playerengine.TaskCatalogue;

import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.task.Goal;
import io.github.thomas16.minemate.task.Goals;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/** Attaches the five temporary hardcoded task verbs to /companion. */
final class CompanionTaskCommands {
    private static final double ARRIVAL_RADIUS = 2.5;

    private CompanionTaskCommands() {
    }

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("follow")
                .executes(ctx -> follow(ctx.getSource(), null))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> follow(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))));
        root.then(resourceCommand("mine"));
        root.then(resourceCommand("fetch"));
        root.then(Commands.literal("goto")
                .then(Commands.argument("pos", ColumnPosArgument.columnPos())
                        .executes(ctx -> goTo(ctx.getSource(), ColumnPosArgument.getColumnPos(ctx, "pos")))));
        root.then(Commands.literal("kill")
                .then(Commands.argument("target", EntityArgument.entity())
                        .executes(ctx -> kill(ctx.getSource(), EntityArgument.getEntity(ctx, "target")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> resourceCommand(String kind) {
        RequiredArgumentBuilder<CommandSourceStack, String> resource = Commands
                .argument("resource", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ResourceNames.values(), builder))
                .executes(ctx -> collect(ctx.getSource(), kind,
                        StringArgumentType.getString(ctx, "resource"), 1));
        resource.then(Commands.argument("count", IntegerArgumentType.integer(1, 2304))
                .executes(ctx -> collect(ctx.getSource(), kind,
                        StringArgumentType.getString(ctx, "resource"),
                        IntegerArgumentType.getInteger(ctx, "count"))));
        return Commands.literal(kind).then(resource);
    }

    private static int follow(CommandSourceStack source, ServerPlayer requestedTarget) {
        ServerPlayer sender = CompanionCommands.requirePlayer(source);
        if (sender == null) {
            return 0;
        }
        ServerPlayer target = requestedTarget == null ? sender : requestedTarget;
        return executeGoal(source, companion -> Goals.follow(target, 3.0));
    }

    private static int collect(CommandSourceStack source, String kind, String resource, int count) {
        return executeGoal(source, companion -> "mine".equals(kind)
                ? Goals.mine(resource, count) : Goals.fetch(resource, count));
    }

    private static int goTo(CommandSourceStack source, ColumnPos position) {
        return executeGoal(source, companion -> Goals.goTo(position.x(), position.z(), ARRIVAL_RADIUS));
    }

    private static int kill(CommandSourceStack source, Entity target) {
        ServerPlayer sender = CompanionCommands.requirePlayer(source);
        if (sender == null) {
            return 0;
        }
        CompanionEntity companion = CompanionCommands.findCompanion(sender);
        if (companion == null) {
            source.sendFailure(Component.literal("No companion nearby — /companion spawn first"));
            return 0;
        }
        if (target == sender || target == companion) {
            source.sendFailure(Component.literal("The companion cannot attack that target"));
            return 0;
        }
        if (!(target.level() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("Target is not in a server world"));
            return 0;
        }
        return submit(source, companion,
                () -> Goals.killEntity(level, target.getUUID(), target.getName().getString()));
    }

    private static int executeGoal(CommandSourceStack source, Function<CompanionEntity, Goal> factory) {
        ServerPlayer player = CompanionCommands.requirePlayer(source);
        if (player == null) {
            return 0;
        }
        CompanionEntity companion = CompanionCommands.findCompanion(player);
        if (companion == null) {
            source.sendFailure(Component.literal("No companion nearby — /companion spawn first"));
            return 0;
        }
        return submit(source, companion, () -> factory.apply(companion));
    }

    private static int submit(CommandSourceStack source, CompanionEntity companion, GoalFactory factory) {
        try {
            Goal goal = factory.create();
            companion.getSupervisor().replace(goal);
            source.sendSuccess(() -> Component.literal("Ordered companion to " + goal.description()), false);
            return 1;
        } catch (IllegalArgumentException error) {
            String message = error.getMessage() == null ? "Invalid companion task" : error.getMessage();
            if (message.startsWith("unknown_resource:")) {
                message = "Unknown resource: " + message.substring("unknown_resource:".length()).trim();
            }
            source.sendFailure(Component.literal(message));
            return 0;
        }
    }

    @FunctionalInterface
    private interface GoalFactory {
        Goal create();
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
