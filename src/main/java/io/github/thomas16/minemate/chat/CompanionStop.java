package io.github.thomas16.minemate.chat;

import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.task.Reasons;

/**
 * The one deterministic stop sequence, shared verbatim by {@code /companion stop} and the exact
 * chat stop forms, so the two paths cannot drift. No API call is involved and nothing pending may
 * revive the work afterwards.
 */
public final class CompanionStop {

    private CompanionStop() {
    }

    public static void stop(CompanionEntity companion) {
        // Unconditional and first: a planner request can be in flight while the supervisor is
        // busy with a manual order, and queued owner turns and any deferred replan die with it.
        companion.getConversationCoordinator().invalidateAll();
        // Stop means stop: no later event may revive this order through the replan path.
        companion.closeStandingOrder();
        // The plan must be cancelled first. Cancelling only the supervisor lets the executor
        // start the very next step on the following tick, so stop would appear to do nothing.
        if (companion.getPlanExecutor().isActive()) {
            companion.getPlanExecutor().cancel(Reasons.CANCELLED_BY_OWNER);
        } else if (companion.getSupervisor().isBusy()) {
            companion.getSupervisor().cancel(Reasons.CANCELLED_BY_OWNER);
        } else {
            companion.getController().cancelUserTask();
        }
    }
}
