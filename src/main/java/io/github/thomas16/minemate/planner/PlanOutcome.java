package io.github.thomas16.minemate.planner;

import java.util.List;
import java.util.Objects;

/**
 * The structured terminal result of a plan: the plan-level analogue of a task outcome, and the
 * signal a stage-5 planner learns from.
 *
 * @param completedSteps steps that finished successfully, so it is less than {@code totalSteps}
 *                       whenever the plan aborted partway
 */
public record PlanOutcome(String planId, PlanStatus status, String reason, int completedSteps,
        int totalSteps, List<PlanStepOutcome> stepOutcomes, long elapsedMs) {

    public PlanOutcome {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(status, "status");
        reason = reason == null ? "" : reason;
        stepOutcomes = List.copyOf(Objects.requireNonNull(stepOutcomes, "stepOutcomes"));
    }

    public boolean succeeded() {
        return status.succeeded();
    }

    /** ASCII single line, e.g. {@code "plan-3 FAILED no_fuel 1/3 8s"}. */
    public String toCompactString() {
        return "%s %s %s %d/%d %ds".formatted(planId, status, reason,
                completedSteps, totalSteps, elapsedMs / 1000L);
    }
}
