package io.github.thomas16.minemate.context;

/**
 * Why the companion should reconsider what it is doing.
 *
 * <p>Declaration order is dispatch priority: when several are pending on the same tick, the first
 * one declared wins and the rest stay pending for a later tick.
 */
public enum ReplanReason {
    DAMAGE_TAKEN,
    TASK_OUTCOME,
    PLAN_FINISHED,
    OWNER_LOST,
    NIGHTFALL,
    OWNER_MOVED_FAR,
    IDLE
}
