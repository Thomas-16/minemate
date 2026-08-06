package io.github.thomas16.minemate.context;

/** The kinds of thing worth remembering happened. Ordered roughly by how much a planner cares. */
public enum EventKind {
    GOAL_STARTED,
    GOAL_FINISHED,
    PLAN_STARTED,
    PLAN_FINISHED,
    DAMAGE_TAKEN,
    DIED,
    REFLEX_STARTED,
    REFLEX_ENDED,
    ITEM_PICKED_UP,
    DELIVERY_RECEIPT,
    OWNER_ARRIVED,
    OWNER_LEFT,
    OWNER_MOVED_FAR,
    NIGHTFALL,
    DAYBREAK,
    OWNER_MESSAGE,
    PLANNER_FAILED,
    PLANNER_BUDGET_EXHAUSTED
}
