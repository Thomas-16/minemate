package io.github.thomas16.minemate.task;

/** Machine-readable reason tokens used by task outcomes. */
public final class Reasons {
    public static final String GOAL_MET = "goal_met";
    public static final String NO_PROGRESS = "no_progress";
    public static final String TIMEOUT = "timeout";
    public static final String CANCELLED_BY_OWNER = "cancelled_by_owner";
    public static final String SUPERSEDED = "superseded";
    public static final String COMPANION_DIED = "companion_died";
    public static final String COMPANION_REMOVED = "companion_removed";
    public static final String ENGINE_FAILED = "engine_failed";
    public static final String ENGINE_BLOCKED = "engine_blocked";
    public static final String ENGINE_FALSE_SUCCESS = "engine_false_success";
    public static final String UNKNOWN_RESOURCE = "unknown_resource";
    public static final String RESOURCE_UNAVAILABLE = "resource_unavailable";
    public static final String PREEMPTED = "preempted";
    public static final String TARGET_GONE = "target_gone";
    public static final String OWNER_OFFLINE = "owner_offline";
    public static final String NO_CRAFTING_RECIPE = "no_crafting_recipe";
    public static final String MATERIALS_UNAVAILABLE = "materials_unavailable";
    public static final String NO_SMELTING_RECIPE = "no_smelting_recipe";
    public static final String NO_FUEL = "no_fuel";
    public static final String PARTIAL_OUT_OF_FUEL = "partial_out_of_fuel";
    public static final String FURNACE_TAMPERED = "furnace_tampered";
    public static final String FURNACE_GONE = "furnace_gone";
    public static final String FURNACE_IN_USE = "furnace_in_use";
    public static final String SMELT_STALLED = "smelt_stalled";
    public static final String SETUP_FAILED = "setup_failed";
    public static final String INSUFFICIENT_ITEMS = "insufficient_items";
    public static final String INSUFFICIENT_SPACE = "insufficient_space";
    public static final String CONTAINER_MISSING = "container_missing";
    public static final String CONTAINER_TOO_FAR = "container_too_far";
    public static final String CONTAINER_UNREACHABLE = "container_unreachable";
    public static final String CONTAINER_UNSUPPORTED = "container_unsupported";
    public static final String CONTAINER_CHANGED = "container_changed";

    // Stage 4: typed tool-call validation. These reject a request before any task is submitted.
    public static final String UNKNOWN_TOOL = "unknown_tool";
    public static final String UNKNOWN_ARGUMENT = "unknown_argument";
    public static final String MISSING_ARGUMENT = "missing_argument";
    public static final String INVALID_ARGUMENT = "invalid_argument";
    public static final String ARGUMENT_OUT_OF_RANGE = "argument_out_of_range";
    public static final String UNKNOWN_ITEM = "unknown_item";

    // Stage 4: multi-step plan execution.
    public static final String PLAN_EMPTY = "plan_empty";
    public static final String PLAN_TOO_LONG = "plan_too_long";
    public static final String PLANNER_UNAVAILABLE = "planner_unavailable";

    private Reasons() {
    }
}
