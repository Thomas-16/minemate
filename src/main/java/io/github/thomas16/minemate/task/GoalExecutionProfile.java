package io.github.thomas16.minemate.task;

import java.util.Objects;

/** Planner-relevant truth about what a goal is allowed to do beneath its public name. */
public record GoalExecutionProfile(ExecutionScope scope, boolean mayAcquirePrerequisites,
        String sideEffects) {

    public GoalExecutionProfile {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(sideEffects, "sideEffects");
    }

    public static GoalExecutionProfile direct(String sideEffects) {
        return new GoalExecutionProfile(ExecutionScope.DIRECT, false, sideEffects);
    }

    public static GoalExecutionProfile composite(String sideEffects) {
        return new GoalExecutionProfile(ExecutionScope.COMPOSITE, true, sideEffects);
    }

    public static GoalExecutionProfile continuous(String sideEffects) {
        return new GoalExecutionProfile(ExecutionScope.CONTINUOUS, false, sideEffects);
    }
}
