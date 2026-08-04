package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.tool.ToolCall;

import java.util.Objects;

/**
 * One step of a plan.
 *
 * @param rationale why the planner chose this step; carried for logs and future reflection, never
 *                  used to decide anything
 */
public record PlanStep(ToolCall call, String rationale) {

    public PlanStep {
        Objects.requireNonNull(call, "call");
        rationale = rationale == null ? "" : rationale;
    }

    public static PlanStep of(ToolCall call) {
        return new PlanStep(call, "");
    }
}
