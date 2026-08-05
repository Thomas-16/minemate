package io.github.thomas16.minemate.task.sink;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.task.Goal;
import io.github.thomas16.minemate.task.GoalProgress;
import io.github.thomas16.minemate.task.OutcomeStatus;
import io.github.thomas16.minemate.task.TaskListener;
import io.github.thomas16.minemate.task.TaskOutcome;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Sends task lifecycle messages only to the companion's online owner. */
public final class ChatTaskListener implements TaskListener {
    private static final long REPEAT_AFTER_MS = 10_000L;

    private final ConcurrentHashMap<UUID, ProgressState> progressStates = new ConcurrentHashMap<>();

    @Override
    public void onGoalStarted(CompanionEntity companion, Goal goal) {
        ServerPlayer owner = owner(companion);
        if (owner != null) {
            owner.sendSystemMessage(message("Started: " + goal.description(), ChatFormatting.GRAY));
        }
    }

    @Override
    public void onGoalProgress(CompanionEntity companion, Goal goal, GoalProgress progress) {
        ServerPlayer owner = owner(companion);
        if (owner == null) {
            return;
        }
        long now = System.currentTimeMillis();
        ProgressState previous = progressStates.get(companion.getUUID());
        if (previous != null && previous.goalId().equals(goal.id()) && previous.have() == progress.have()
                && previous.text().equals(progress.text())
                && now - previous.sentAtMs() < REPEAT_AFTER_MS) {
            return;
        }
        progressStates.put(companion.getUUID(), new ProgressState(goal.id(), progress.have(),
                progress.text(), now));
        owner.sendSystemMessage(message(progress.text(), ChatFormatting.GRAY));
    }

    @Override
    public void onGoalFinished(CompanionEntity companion, Goal goal, TaskOutcome outcome) {
        progressStates.remove(companion.getUUID());
        ServerPlayer owner = owner(companion);
        if (owner == null) {
            return;
        }
        ChatFormatting color = outcome.status() == OutcomeStatus.SUCCEEDED
                ? ChatFormatting.GREEN
                : outcome.status() == OutcomeStatus.FAILED ? ChatFormatting.RED : ChatFormatting.YELLOW;
        owner.sendSystemMessage(message(outcome.detail(), color));
    }

    private static ServerPlayer owner(CompanionEntity companion) {
        if (companion.getServer() == null) {
            MineMate.LOGGER.debug("No server available for companion {} task message", companion.getUUID());
            return null;
        }
        ServerPlayer owner = companion.getOwnerUuid()
                .map(uuid -> companion.getServer().getPlayerList().getPlayer(uuid))
                .orElse(null);
        if (owner == null) {
            MineMate.LOGGER.debug("No online owner for companion {} task message", companion.getUUID());
        }
        return owner;
    }

    private static Component message(String text, ChatFormatting color) {
        return Component.literal("[MineMate] " + text).withStyle(color);
    }

    private record ProgressState(String goalId, int have, String text, long sentAtMs) {
    }
}
