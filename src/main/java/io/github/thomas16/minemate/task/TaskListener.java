package io.github.thomas16.minemate.task;

import io.github.thomas16.minemate.entity.CompanionEntity;

/** Receives immutable goal lifecycle events. */
public interface TaskListener {
    default void onGoalStarted(CompanionEntity companion, Goal goal) {
    }

    default void onGoalProgress(CompanionEntity companion, Goal goal, GoalProgress progress) {
    }

    default void onGoalFinished(CompanionEntity companion, Goal goal, TaskOutcome outcome) {
    }
}
