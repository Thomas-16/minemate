package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.context.ConversationLog;
import io.github.thomas16.minemate.context.ReplanReason;
import io.github.thomas16.minemate.context.WorldSnapshot;

import java.util.List;
import java.util.Objects;

/**
 * Everything a planner is given: what was asked, what the world looks like, why it is being asked
 * now, the conversation so far, and the tools it may use.
 *
 * @param companionName   the companion's stable personal name; blank only for an unnamed legacy
 *                        or test entity
 * @param origin          why this request is being made; decides whether a speech-only reply is
 *                        conversation or a protocol violation
 * @param renderedContext the snapshot already rendered, so a planner never re-derives the prompt
 * @param trigger         the event that prompted this call; null for a direct owner request
 * @param repairNote      why the previous reply for this order was rejected, quoted back to the
 *                        model for its one bounded repair attempt; null for an ordinary request
 * @param ledgerText      the standing order's delivery ledger rendered for the prompt; blank for
 *                        a fresh order, which has no ledger until its contract is declared
 * @param transcript      the bounded conversation before this request's own text was recorded,
 *                        oldest first; empty when no conversational memory exists
 * @param ownerName       the owner's display name at request time; blank when the owner is
 *                        offline or unresolved
 */
public record PlanRequest(String requestText, String companionName, RequestOrigin origin,
        WorldSnapshot snapshot, String renderedContext, ReplanReason trigger,
        String toolSchemaJson, String repairNote, String ledgerText,
        List<ConversationLog.Entry> transcript, String ownerName) {

    public PlanRequest {
        Objects.requireNonNull(snapshot, "snapshot");
        requestText = requestText == null ? "" : requestText;
        companionName = companionName == null ? "" : companionName;
        origin = origin == null ? RequestOrigin.OWNER_CHAT : origin;
        renderedContext = renderedContext == null ? "" : renderedContext;
        toolSchemaJson = toolSchemaJson == null ? "" : toolSchemaJson;
        ledgerText = ledgerText == null ? "" : ledgerText;
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
        ownerName = ownerName == null ? "" : ownerName;
    }

    /** Transcript-less convenience for tests and probes that assert on the newest turn only. */
    public PlanRequest(String requestText, String companionName, RequestOrigin origin,
            WorldSnapshot snapshot, String renderedContext, ReplanReason trigger,
            String toolSchemaJson) {
        this(requestText, companionName, origin, snapshot, renderedContext, trigger,
                toolSchemaJson, null, null, List.of(), "");
    }
}
