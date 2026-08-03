package io.github.thomas16.minemate.task;

/** Structured terminal result consumed by chat, logs, tests, and the future planner. */
public record TaskOutcome(String goalId, String kind, OutcomeStatus status, String reason,
        String detail, int moved, int have, int want, long elapsedMs,
        String engineState, String engineLog) {

    public boolean succeeded() {
        return status.succeeded();
    }

    public String toCompactString() {
        String movedText = moved < 0 ? "" : " moved=" + moved;
        return "%s %s %s %d/%d%s %ds".formatted(
                kind, status, reason, have, want, movedText, elapsedMs / 1000L);
    }
}
