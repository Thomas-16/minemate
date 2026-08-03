package io.github.thomas16.minemate.task;

import java.util.Objects;

/** Immutable canonical telemetry for the currently supervised PlayerEngine task. */
public record TaskActivitySnapshot(String goalId, String kind, GoalExecutionProfile profile,
        int elapsedTicks, int timeoutTicks, String phase, String taskTree,
        GoalProgress progress, String preemptingChain) {

    public TaskActivitySnapshot {
        Objects.requireNonNull(goalId, "goalId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(profile, "profile");
        phase = phase == null ? "" : phase;
        taskTree = taskTree == null ? "" : taskTree;
        preemptingChain = preemptingChain == null ? "" : preemptingChain;
    }

    public boolean preempted() {
        return !preemptingChain.isEmpty();
    }
}
