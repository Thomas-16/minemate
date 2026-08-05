package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.context.EventKind;
import io.github.thomas16.minemate.context.WorldSnapshot;
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
 * chance to get it wrong. The hosted planner added in stage 5 needed no change to any of that; the
 * one thing it did add is explicit terminal dispositions, because speech alone must never be
 * mistaken for proof that an order is complete.
 */
public final class PlannerDispatch {

    /** Owner-facing close message when a reply stays semantically incomplete after its repair. */
    public static final String SEMANTIC_CLOSE_MESSAGE =
            "I could not put together a complete plan for that order, so I am setting it aside.";

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
                server.execute(() -> apply(companion, planner, request, plan, error, generation)));
    }

    private static void apply(CompanionEntity companion, Planner planner, PlanRequest request,
            Plan plan, Throwable error, int generation) {
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
            companion.closeStandingOrder();
            return;
        }
        if (plan.disposition().terminal()) {
            if (!plan.steps().isEmpty() || plan.dialogue().isBlank()) {
                MineMate.LOGGER.warn("Planner {} produced an invalid terminal plan", planner.name());
                if (companion.getContext() != null) {
                    companion.getContext().record(EventKind.PLANNER_FAILED,
                            "planning failed (invalid terminal plan)", planner.name());
                }
                tell(companion, ToolMessages.owner(Reasons.PLANNER_UNAVAILABLE, ""));
                companion.closeStandingOrder();
                return;
            }
            tell(companion, plan.dialogue());
            companion.closeStandingOrder();
            return;
        }
        if (planner.requiresContract()) {
            StandingOrder order = companion.getStandingOrder();
            if (order != null) {
                String violation = null;
                if (plan.steps().isEmpty()) {
                    if (plan.contract() != null && order.contract() == null) {
                        // The single-call habit: the model declares the contract and stops. Live
                        // sessions show a repair prompt often just reproduces the habit, so do not
                        // spend the repair on it. The contract itself is honest about what the
                        // order needs: record it and ask for the world calls as a continuation.
                        // Coverage is still enforced on that follow-up via awaitingCoverage.
                        order.declareContract(plan.contract());
                        order.markAwaitingCoverage();
                        continueForCoverage(companion, planner, request, plan);
                        return;
                    }
                    violation = "the reply declared a contract but no world-tool calls";
                } else if (order.contract() == null) {
                    // First accepted action reply not seen yet: this reply must declare the
                    // contract, and its own steps must honor it.
                    violation = plan.contract() == null
                            ? "the action reply did not declare the order contract"
                            : ContractCoverage.violation(plan.contract(), plan.steps());
                } else if (order.awaitingCoverage()) {
                    // The stored contract arrived alone and nothing has executed since, so this
                    // first real plan is judged against the whole contract, exactly as if the
                    // model had sent contract and calls together.
                    violation = ContractCoverage.violation(order.contract(), plan.steps());
                } else if (plan.contract() != null) {
                    // A replan re-declaring the contract is tolerated and ignored: the stored one
                    // is authoritative, and replans plan only the remaining work, which cannot be
                    // judged against the full contract without delivery evidence.
                    MineMate.LOGGER.debug("Planner {} re-declared the order contract on a replan",
                            planner.name());
                }
                if (violation != null) {
                    repairOrClose(companion, planner, request, plan, violation);
                    return;
                }
                if (order.contract() == null) {
                    order.declareContract(plan.contract());
                }
                order.clearAwaitingCoverage();
            }
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

    /**
     * The free follow-up for a contract-only first reply: the declared contract is stored, the
     * model's speech reaches the owner, and one more request asks for the world-tool calls. Not
     * charged against the repair, which stays reserved for a plan that violates the contract; a
     * model that sends the contract alone again after this goes through the ordinary repair path
     * and then closes.
     */
    private static void continueForCoverage(CompanionEntity companion, Planner planner,
            PlanRequest request, Plan plan) {
        MineMate.LOGGER.info(
                "Planner {} declared the order contract without world calls; requesting the work",
                planner.name());
        if (!plan.dialogue().isBlank()) {
            tell(companion, plan.dialogue());
        }
        String note = "it declared the order contract, which is now recorded, but contained no"
                + " world-tool calls";
        WorldSnapshot snapshot = companion.getContext().snapshot();
        request(companion, planner, new PlanRequest(request.requestText(), snapshot,
                snapshot.render(), request.trigger(), request.toolSchemaJson(), note));
    }

    /**
     * One bounded repair per order: quote the violation and the rejected reply back to the model,
     * or close the order once that chance is spent. The rejected plan never reaches the executor
     * either way, so a semantically incomplete plan cannot half-execute.
     */
    private static void repairOrClose(CompanionEntity companion, Planner planner,
            PlanRequest request, Plan plan, String violation) {
        StandingOrder order = companion.getStandingOrder();
        if (order == null || !order.repairAvailable()) {
            MineMate.LOGGER.warn("Planner {} reply rejected after repair: {}", planner.name(),
                    violation);
            if (companion.getContext() != null) {
                companion.getContext().record(EventKind.PLANNER_FAILED,
                        "planning failed (" + violation + ")", planner.name());
            }
            tell(companion, SEMANTIC_CLOSE_MESSAGE);
            companion.closeStandingOrder();
            return;
        }
        order.consumeRepair();
        MineMate.LOGGER.info("Planner {} reply rejected, requesting repair: {}", planner.name(),
                violation);
        String note = violation + "; the rejected reply said \"" + plan.dialogue()
                + "\" and called " + plan.steps().stream()
                        .map(step -> step.call().toCompactString()).toList();
        // A fresh snapshot: seconds passed during the round trip, and snapshots memoize per tick.
        // Requesting synchronously on this same server-thread slice re-raises the pending flag
        // before PlannerAutonomy can interleave, and /companion stop still disowns the repair
        // through the ordinary generation counter.
        WorldSnapshot snapshot = companion.getContext().snapshot();
        request(companion, planner, new PlanRequest(request.requestText(), snapshot,
                snapshot.render(), request.trigger(), request.toolSchemaJson(), note));
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    static void tell(CompanionEntity companion, String message) {
        companion.resolveOwner().ifPresent(owner ->
                owner.sendSystemMessage(Component.literal("[MineMate] " + message)));
    }
}
