package io.github.thomas16.minemate.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.entity.MineMateEntities;
import io.github.thomas16.minemate.task.Goal;
import io.github.thomas16.minemate.task.GoalProgress;
import io.github.thomas16.minemate.task.Reasons;
import io.github.thomas16.minemate.task.TaskOutcome;
import io.github.thomas16.minemate.task.TaskActivitySnapshot;
import io.github.thomas16.minemate.integration.ReflexSnapshot;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/** Temporary developer commands for spawning and directing the companion. */
public final class CompanionCommands {

    private CompanionCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext registryAccess) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("companion");
        root.then(Commands.literal("spawn").executes(ctx -> spawn(ctx.getSource())));
        root.then(Commands.literal("dismiss").executes(ctx -> dismiss(ctx.getSource())));
        root.then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())));
        root.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())));
        CompanionTaskCommands.attach(root, registryAccess);
        dispatcher.register(root);
    }

    private static int stop(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        CompanionEntity companion = findCompanion(player);
        if (companion == null) {
            source.sendFailure(Component.literal("No companion nearby"));
            return 0;
        }
        if (companion.getSupervisor().isBusy()) {
            companion.getSupervisor().cancel(Reasons.CANCELLED_BY_OWNER);
        } else {
            companion.getController().cancelUserTask();
        }
        source.sendSuccess(() -> Component.literal("Companion stopped"), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        CompanionEntity companion = findCompanion(player);
        if (companion == null) {
            source.sendFailure(Component.literal("No companion nearby"));
            return 0;
        }
        Optional<Goal> current = companion.getSupervisor().currentGoal();
        if (current.isPresent()) {
            Optional<GoalProgress> progress = companion.getSupervisor().currentProgress();
            String suffix = progress.map(value -> " — " + value.text()).orElse(" — waiting to start");
            Optional<TaskActivitySnapshot> activity = companion.getSupervisor().currentActivity();
            String scope = " — " + current.get().executionProfile().scope().name().toLowerCase();
            String phase = activity.map(TaskActivitySnapshot::phase)
                    .filter(value -> !value.isBlank() && progress.map(p -> !value.equals(p.text())).orElse(true))
                    .map(value -> " — " + value).orElse("");
            String paused = activity.filter(TaskActivitySnapshot::preempted)
                    .map(value -> " — paused: " + value.preemptingChain()).orElse("");
            String budget = activity.map(value -> " — ticks " + value.elapsedTicks() + "/"
                    + (value.timeoutTicks() == 0 ? "unbounded" : value.timeoutTicks())).orElse("");
            String taskTree = activity.map(TaskActivitySnapshot::taskTree)
                    .filter(value -> !value.isBlank())
                    .map(value -> " — task: " + bounded(value, 180)).orElse("");
            String reflex = reflexSuffix(companion.getReflexSnapshot());
            source.sendSuccess(
                    () -> Component.literal("Current: " + current.get().description()
                            + scope + suffix + budget + phase + taskTree + paused + reflex), false);
            return 1;
        }
        Optional<TaskOutcome> outcome = companion.getSupervisor().lastOutcome();
        String text = outcome.map(value -> "Idle — last: " + value.detail()).orElse("Idle")
                + reflexSuffix(companion.getReflexSnapshot());
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static String reflexSuffix(ReflexSnapshot reflex) {
        if (reflex == null || !reflex.active()) {
            return reflex != null && reflex.defenseSuppressed() ? " — mob defense leashed" : "";
        }
        String detail = reflex.detail().isBlank() ? "" : ": " + reflex.detail();
        return " — reflex " + reflex.kind().name().toLowerCase() + detail;
    }

    private static String bounded(String value, int maximum) {
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= maximum ? oneLine : oneLine.substring(0, maximum - 1) + "…";
    }

    private static int spawn(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
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
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
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

    static ServerPlayer requirePlayer(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player-only command"));
        }
        return player;
    }

    /** Finds the player's own companion within 128 blocks. */
    static CompanionEntity findCompanion(ServerPlayer player) {
        List<CompanionEntity> companions = player.serverLevel().getEntitiesOfClass(
                CompanionEntity.class,
                player.getBoundingBox().inflate(128.0),
                candidate -> candidate.isAlive()
                        && candidate.getOwnerUuid().map(player.getUUID()::equals).orElse(false));
        return companions.isEmpty() ? null : companions.get(0);
    }
}
