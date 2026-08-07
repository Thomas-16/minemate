package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.context.ConversationLog;
import io.github.thomas16.minemate.context.WorldSnapshot;
import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.tool.ToolRegistry;

import java.util.List;

/**
 * Builds and dispatches planner requests so every producer captures conversation state the same
 * way. The command path and the headless harness both go through {@link #owner}, so what the
 * suite exercises cannot drift from what the player uses.
 */
public final class PlannerRequests {

    private PlannerRequests() {
    }

    /**
     * A fresh owner utterance. The transcript is snapshotted <em>before</em> the new text is
     * recorded, so the utterance appears exactly once on the wire: in the newest turn, never
     * duplicated into history.
     */
    public static void owner(CompanionEntity companion, Planner planner, String text) {
        ConversationLog log = companion.getConversationLog();
        List<ConversationLog.Entry> transcript = log == null ? List.of() : log.snapshot();
        if (log != null) {
            log.recordOwner(text, companion.level().getGameTime());
        }
        WorldSnapshot snapshot = companion.getContext().snapshot();
        PlannerDispatch.request(companion, planner, new PlanRequest(text,
                companion.getCompanionName().orElse(""), RequestOrigin.OWNER_CHAT, snapshot,
                snapshot.render(), null, ToolRegistry.defaults().schemaJson(), null, null,
                transcript, ownerNameOf(companion)));
    }

    /** The conversation as it stands, for non-owner requests built at their own call sites. */
    public static List<ConversationLog.Entry> transcriptOf(CompanionEntity companion) {
        ConversationLog log = companion.getConversationLog();
        return log == null ? List.of() : log.snapshot();
    }

    public static String ownerNameOf(CompanionEntity companion) {
        return companion.resolveOwner()
                .map(owner -> owner.getGameProfile().getName())
                .orElse("");
    }
}
