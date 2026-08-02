package io.github.thomas16.minemate.command;

import com.mojang.brigadier.CommandDispatcher;
import com.player2.playerengine.tasks.movement.FollowPlayerTask;

import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.entity.MineMateEntities;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Dev/debug commands for driving the companion before the voice/LLM layers
 * exist. /companion spawn|follow|stop|dismiss
 */
public final class CompanionCommands {

    private CompanionCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("companion")
                .then(Commands.literal("spawn").executes(ctx -> spawn(ctx.getSource())))
                .then(Commands.literal("follow").executes(ctx -> follow(ctx.getSource())))
                .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())))
                .then(Commands.literal("dismiss").executes(ctx -> dismiss(ctx.getSource()))));
    }

    private static int follow(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player-only command"));
            return 0;
        }
        CompanionEntity companion = findCompanion(player);
        if (companion == null) {
            source.sendFailure(Component.literal("No companion nearby — /companion spawn first"));
            return 0;
        }
        companion.getController().runUserTask(
                new FollowPlayerTask(player.getName().getString(), 3.0));
        source.sendSuccess(() -> Component.literal("Companion is following you"), false);
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player-only command"));
            return 0;
        }
        CompanionEntity companion = findCompanion(player);
        if (companion == null) {
            source.sendFailure(Component.literal("No companion nearby"));
            return 0;
        }
        companion.getController().cancelUserTask();
        source.sendSuccess(() -> Component.literal("Companion stopped"), false);
        return 1;
    }

    private static int spawn(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player-only command"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        CompanionEntity existing = findCompanion(player);
        if (existing != null) {
            source.sendFailure(Component.literal("You already have a companion"));
            return 0;
        }
        CompanionEntity companion = new CompanionEntity(MineMateEntities.COMPANION, level);
        companion.setOwnerUuid(player.getUUID());
        BlockPos pos = player.blockPosition().offset(1, 0, 1);
        companion.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), 0.0F);
        level.addFreshEntity(companion);
        source.sendSuccess(() -> Component.literal("Companion spawned"), false);
        return 1;
    }

    private static int dismiss(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player-only command"));
            return 0;
        }
        CompanionEntity companion = findCompanion(player);
        if (companion == null) {
            source.sendFailure(Component.literal("No companion nearby"));
            return 0;
        }
        companion.discard();
        source.sendSuccess(() -> Component.literal("Companion dismissed"), false);
        return 1;
    }

    /** Finds the player's own companion within 128 blocks. */
    static CompanionEntity findCompanion(ServerPlayer player) {
        List<CompanionEntity> companions = player.serverLevel().getEntitiesOfClass(
                CompanionEntity.class,
                player.getBoundingBox().inflate(128.0),
                c -> c.isAlive() && c.getOwnerUuid().map(player.getUUID()::equals).orElse(false));
        return companions.isEmpty() ? null : companions.get(0);
    }
}
