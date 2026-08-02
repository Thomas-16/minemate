package io.github.thomas16.minemate.task;

/** Stable terminal states exposed to future planners and other sinks. */
public enum OutcomeStatus {
    SUCCEEDED,
    FAILED,
    BLOCKED,
    CANCELLED,
    TIMED_OUT;

    public boolean succeeded() {
        return this == SUCCEEDED;
    }

    public boolean retryable() {
        return this == TIMED_OUT || this == BLOCKED;
    }
}
