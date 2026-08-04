package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.task.OutcomeStatus;
import io.github.thomas16.minemate.tool.ToolCall;

import java.util.Objects;

/** What one plan step did, keyed back to the goal the supervisor ran for it. */
public record PlanStepOutcome(int index, ToolCall call, String goalId,
        OutcomeStatus status, String reason, String detail) {

    public PlanStepOutcome {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(status, "status");
        goalId = goalId == null ? "" : goalId;
        reason = reason == null ? "" : reason;
        detail = detail == null ? "" : detail;
    }

    public boolean succeeded() {
        return status.succeeded();
    }
}
