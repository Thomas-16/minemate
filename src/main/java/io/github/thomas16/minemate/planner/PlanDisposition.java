package io.github.thomas16.minemate.planner;

/** Whether a planner reply contains work or closes the current standing order. */
public enum PlanDisposition {
    ACTION,
    COMPLETED,
    CLARIFICATION,
    UNSUPPORTED,
    BLOCKED;

    public boolean terminal() {
        return this != ACTION;
    }
}
