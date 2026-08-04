package io.github.thomas16.minemate.tool;

import io.github.thomas16.minemate.task.Reasons;

/**
 * Owner-facing prose for a rejected or unbindable tool call.
 *
 * <p>Derived from the typed reason token, which is why the command layer no longer parses an
 * exception message to recover what went wrong.
 */
public final class ToolMessages {

    private ToolMessages() {
    }

    /** ASCII only: this text reaches chat and the console. */
    public static String owner(String reason, String detail) {
        String suffix = detail == null || detail.isBlank() ? "" : detail;
        return switch (reason) {
            case Reasons.UNKNOWN_TOOL -> "I do not know how to " + suffix;
            case Reasons.UNKNOWN_RESOURCE -> "Unknown resource: " + suffix;
            case Reasons.UNKNOWN_ITEM -> "Unknown item: " + suffix;
            case Reasons.MISSING_ARGUMENT -> "Missing required argument: " + suffix;
            case Reasons.UNKNOWN_ARGUMENT -> "Unexpected argument: " + suffix;
            case Reasons.ARGUMENT_OUT_OF_RANGE, Reasons.INVALID_ARGUMENT -> suffix.isEmpty()
                    ? "That request has an invalid argument" : suffix;
            case Reasons.TARGET_GONE -> suffix.isEmpty() ? "That target is gone" : suffix;
            case Reasons.OWNER_OFFLINE -> suffix.isEmpty() ? "That player is not online" : suffix;
            case Reasons.PLAN_EMPTY -> "That plan has no steps";
            case Reasons.PLAN_TOO_LONG -> "That plan has too many steps";
            case Reasons.PLANNER_UNAVAILABLE -> "I could not work out a plan for that";
            default -> suffix.isEmpty() ? "That request was rejected (" + reason + ")"
                    : suffix + " (" + reason + ")";
        };
    }
}
