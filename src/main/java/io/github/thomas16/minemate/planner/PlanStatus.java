package io.github.thomas16.minemate.planner;

/** How a plan ended. {@code REJECTED} means it never started: no step was ever submitted. */
public enum PlanStatus {
    SUCCEEDED,
    FAILED,
    BLOCKED,
    CANCELLED,
    REJECTED,
    TIMED_OUT;

    public boolean succeeded() {
        return this == SUCCEEDED;
    }
}
