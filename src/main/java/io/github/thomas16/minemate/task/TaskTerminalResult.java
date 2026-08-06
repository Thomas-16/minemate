package io.github.thomas16.minemate.task;

import java.util.Objects;

/**
 * A structured non-success result reported by a goal verifier.
 *
 * <p>{@code detail} is optional owner-facing prose; when null, the supervisor renders its standard
 * prose for the reason token. A probe supplies it only when the standard prose cannot say what
 * happened, such as a best-effort goal reporting which items were delivered and which were not.
 */
public record TaskTerminalResult(OutcomeStatus status, String reason, int moved, String detail) {

    public TaskTerminalResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        if (status != OutcomeStatus.BLOCKED && status != OutcomeStatus.FAILED
                && status != OutcomeStatus.TIMED_OUT) {
            throw new IllegalArgumentException(
                    "Goal probes may declare only BLOCKED, FAILED, or TIMED_OUT; progress owns success");
        }
    }

    public static TaskTerminalResult blocked(String reason) {
        return new TaskTerminalResult(OutcomeStatus.BLOCKED, reason, -1, null);
    }

    public static TaskTerminalResult blocked(String reason, int moved) {
        return new TaskTerminalResult(OutcomeStatus.BLOCKED, reason, Math.max(0, moved), null);
    }

    public static TaskTerminalResult blocked(String reason, int moved, String detail) {
        return new TaskTerminalResult(OutcomeStatus.BLOCKED, reason, Math.max(0, moved), detail);
    }

    public static TaskTerminalResult failed(String reason) {
        return new TaskTerminalResult(OutcomeStatus.FAILED, reason, -1, null);
    }

    public static TaskTerminalResult timedOut(String reason) {
        return new TaskTerminalResult(OutcomeStatus.TIMED_OUT, reason, -1, null);
    }
}
