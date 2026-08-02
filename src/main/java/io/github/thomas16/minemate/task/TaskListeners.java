package io.github.thomas16.minemate.task;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.entity.CompanionEntity;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Global fan-out for task lifecycle sinks. */
public final class TaskListeners {
    private static final CopyOnWriteArrayList<TaskListener> LISTENERS = new CopyOnWriteArrayList<>();

    private TaskListeners() {
    }

    public static void register(TaskListener listener) {
        LISTENERS.add(Objects.requireNonNull(listener, "listener"));
    }

    static void fireStarted(CompanionEntity companion, Goal goal) {
        dispatch(listener -> listener.onGoalStarted(companion, goal));
    }

    static void fireProgress(CompanionEntity companion, Goal goal, GoalProgress progress) {
        dispatch(listener -> listener.onGoalProgress(companion, goal, progress));
    }

    static void fireFinished(CompanionEntity companion, Goal goal, TaskOutcome outcome) {
        dispatch(listener -> listener.onGoalFinished(companion, goal, outcome));
    }

    private static void dispatch(Consumer<TaskListener> call) {
        for (TaskListener listener : LISTENERS) {
            try {
                call.accept(listener);
            } catch (Throwable error) {
                MineMate.LOGGER.error("Task listener {} failed", listener.getClass().getName(), error);
            }
        }
    }
}
