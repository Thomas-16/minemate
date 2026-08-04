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
 */
public record PlanRequest(String requestText, WorldSnapshot snapshot, String renderedContext,
        ReplanReason trigger, String toolSchemaJson) {

    public PlanRequest {
        Objects.requireNonNull(snapshot, "snapshot");
        requestText = requestText == null ? "" : requestText;
        renderedContext = renderedContext == null ? "" : renderedContext;
        toolSchemaJson = toolSchemaJson == null ? "" : toolSchemaJson;
    }
}
