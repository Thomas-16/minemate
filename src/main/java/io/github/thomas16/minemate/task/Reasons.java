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
    public static final String TARGET_GONE = "target_gone";
    public static final String OWNER_OFFLINE = "owner_offline";

    private Reasons() {
    }
}
