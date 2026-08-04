package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.context.EventKind;
import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.task.Reasons;
import io.github.thomas16.minemate.tool.ToolMessages;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * The single bridge between a planner's asynchronous result and the server thread.
 *
 * <p>Concentrating {@code server.execute(...)} here is deliberate: it is the one place the
 * "never block the tick thread" rule can be got wrong, so no planner implementation is given the
 * chance to get it wrong. A stage-5 HTTP planner needs no changes here at all.
 */
public final class PlannerDispatch {

    private PlannerDispatch() {
    }

    public static void request(CompanionEntity companion, Planner planner, PlanRequest request) {
        MinecraftServer server = companion.getServer();
        PlanExecutor executor = companion.getPlanExecutor();
        if (server == null || executor == null) {
            return;
        }
        // Claimed now, on the server thread, so a later request or a cancel can disown this one.
        int generation = executor.beginPlannerRequest();
        planner.plan(request).whenComplete((plan, error) ->
                server.execute(() -> apply(companion, planner, plan, error, generation)));
    }

    private static void apply(CompanionEntity companion, Planner planner, Plan plan, Throwable error,
            int generation) {
        if (companion.isRemoved() || companion.getPlanExecutor() == null) {
            return;
        }
        if (!companion.getPlanExecutor().isCurrentPlannerRequest(generation)) {
            // The owner cancelled, stopped, or asked for something else while this was in flight.
            // Silently dropped: they already got feedback from whatever superseded it.
            MineMate.LOGGER.debug("Discarding stale plan from planner {}", planner.name());
            return;
        }
        companion.getPlanExecutor().completePlannerRequest(generation);
        if (error != null || plan == null) {
            String detail = error == null ? "no plan produced" : rootMessage(error);
            MineMate.LOGGER.warn("Planner {} failed: {}", planner.name(), detail);
            if (companion.getContext() != null) {
                companion.getContext().record(EventKind.PLANNER_FAILED,
                        "planning failed (" + detail + ")", planner.name());
            }
            tell(companion, ToolMessages.owner(Reasons.PLANNER_UNAVAILABLE, ""));
            return;
        }
        PlanExecutor.StartResult result = companion.getPlanExecutor().start(plan);
        switch (result) {
            case ACCEPTED -> {
                if (!plan.dialogue().isBlank()) {
                    tell(companion, plan.dialogue());
                }
            }
            case BUSY -> tell(companion, "I am already working on a plan");
            case REJECTED -> tell(companion, companion.getPlanExecutor().lastOutcome()
                    .map(outcome -> ToolMessages.owner(outcome.reason(), ""))
                    .orElse("I could not use that plan"));
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static void tell(CompanionEntity companion, String message) {
        companion.resolveOwner().ifPresent(owner ->
                owner.sendSystemMessage(Component.literal("[MineMate] " + message)));
    }
}
