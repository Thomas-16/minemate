package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.context.ReplanTrigger;
import io.github.thomas16.minemate.context.WorldSnapshot;
import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.tool.ToolRegistry;

/**
 * Turns replan triggers into planner requests, within a hard budget.
 *
 * <p>This is the whole of stage 5's autonomy, and it is deliberately small. Only the three reasons
 * that carry an outcome the model could act on are allowed through, and only while an owner order
 * is open: an idle companion, nightfall and a departing owner never spend a request. Every call is
 * charged against {@link StandingOrder}, so a single order has a worst case of one request plus
 * {@code maxReplansPerOrder} follow-ups no matter how many events fire.
 *
 * <p>The outcome itself needs no plumbing. The fresh snapshot's {@code LAST} and {@code EVENTS}
 * sections already carry how the last task ended, so a replan request is the same request with the
 * trigger attached.
 */
public final class PlannerAutonomy {

    private PlannerAutonomy() {
    }

    public static void onReplanTrigger(CompanionEntity companion, ReplanTrigger trigger) {
        MineMate.LOGGER.debug("Replan trigger {} for companion {}: {}",
                trigger.reason(), companion.getUUID(), trigger.detail());

        if (!(Planners.active() instanceof HostedPlanner planner)) {
            // The scripted stand-in answers only fixed keys, and a headless run must never be able
            // to reach the network. Autonomy belongs to a real planner or to nobody.
            return;
        }
        switch (trigger.reason()) {
            case PLAN_FINISHED, TASK_OUTCOME, DAMAGE_TAKEN -> {
            }
            default -> {
                return;
            }
        }

        StandingOrder order = companion.getStandingOrder();
        if (order == null) {
            return;
        }
        int budget = planner.config().maxReplansPerOrder();
        if (!order.canReplan(budget)) {
            MineMate.LOGGER.debug("Replan budget spent for order {}", order);
            companion.closeStandingOrder();
            return;
        }

        PlanExecutor executor = companion.getPlanExecutor();
        if (executor == null || executor.isActive() || executor.isPlannerRequestPending()) {
            return;
        }
        if (companion.getSupervisor() != null && companion.getSupervisor().isBusy()) {
            // Still carrying out a manual order. Replanning now would only be told BUSY.
            return;
        }

        order.consumeReplan();
        WorldSnapshot snapshot = companion.getContext().snapshot();
        PlannerDispatch.request(companion, planner, new PlanRequest(order.requestText(), snapshot,
                snapshot.render(), trigger.reason(), ToolRegistry.defaults().schemaJson()));
    }
}
