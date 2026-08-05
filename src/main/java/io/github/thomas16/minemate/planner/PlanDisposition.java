package io.github.thomas16.minemate.planner;

/** Whether a planner reply contains work or closes the current standing order. */
public enum PlanDisposition {
    ACTION,
    COMPLETED,
    /** A question or social remark answered from the snapshot; no world order was performed. */
    ANSWERED,
    CLARIFICATION,
    UNSUPPORTED,
    BLOCKED;

    public boolean terminal() {
        return this != ACTION;
    }
}
