package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.context.ReplanReason;
import io.github.thomas16.minemate.context.WorldSnapshot;

import java.util.Objects;

/**
 * Everything a planner is given: what was asked, what the world looks like, why it is being asked
 * now, and the tools it may use.
 *
 * @param renderedContext the snapshot already rendered, so a planner never re-derives the prompt
 * @param trigger         the event that prompted this call; null for a direct owner request
 * @param repairNote      why the previous reply for this order was rejected, quoted back to the
 *                        model for its one bounded repair attempt; null for an ordinary request
 */
public record PlanRequest(String requestText, WorldSnapshot snapshot, String renderedContext,
        ReplanReason trigger, String toolSchemaJson, String repairNote) {

    public PlanRequest {
        Objects.requireNonNull(snapshot, "snapshot");
        requestText = requestText == null ? "" : requestText;
        renderedContext = renderedContext == null ? "" : renderedContext;
        toolSchemaJson = toolSchemaJson == null ? "" : toolSchemaJson;
    }

    public PlanRequest(String requestText, WorldSnapshot snapshot, String renderedContext,
            ReplanReason trigger, String toolSchemaJson) {
        this(requestText, snapshot, renderedContext, trigger, toolSchemaJson, null);
    }
}
