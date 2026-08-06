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
 * @param ledgerText      the standing order's delivery ledger rendered for the prompt; blank for
 *                        a fresh order, which has no ledger until its contract is declared
 */
public record PlanRequest(String requestText, WorldSnapshot snapshot, String renderedContext,
        ReplanReason trigger, String toolSchemaJson, String repairNote, String ledgerText) {

    public PlanRequest {
        Objects.requireNonNull(snapshot, "snapshot");
        requestText = requestText == null ? "" : requestText;
        renderedContext = renderedContext == null ? "" : renderedContext;
        toolSchemaJson = toolSchemaJson == null ? "" : toolSchemaJson;
        ledgerText = ledgerText == null ? "" : ledgerText;
    }

    public PlanRequest(String requestText, WorldSnapshot snapshot, String renderedContext,
            ReplanReason trigger, String toolSchemaJson, String repairNote) {
        this(requestText, snapshot, renderedContext, trigger, toolSchemaJson, repairNote, null);
    }

    public PlanRequest(String requestText, WorldSnapshot snapshot, String renderedContext,
            ReplanReason trigger, String toolSchemaJson) {
        this(requestText, snapshot, renderedContext, trigger, toolSchemaJson, null, null);
    }
}
