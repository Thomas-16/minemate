package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.chat.CompanionSpeech;
import io.github.thomas16.minemate.context.EventKind;
import io.github.thomas16.minemate.context.ReplanReason;
import io.github.thomas16.minemate.context.WorldSnapshot;
import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.task.Reasons;
import io.github.thomas16.minemate.tool.ToolCall;
import io.github.thomas16.minemate.tool.ToolMessages;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * The single bridge between a planner's asynchronous result and the server thread.
 *
 * <p>Concentrating {@code server.execute(...)} here is deliberate: it is the one place the
 * "never block the tick thread" rule can be got wrong, so no planner implementation is given the
 * chance to get it wrong. Dispatch is also the semantic gate on a {@link CompanionResponse}:
 * speech is conversation, an order close needs evidence, and actions become a {@link Plan} only
 * after every contract check passes -- speech alone must never be mistaken for proof that an
 * order is complete.
 */
public final class PlannerDispatch {

    /** Owner-facing close message when a reply stays semantically incomplete after its repair. */
    public static final String SEMANTIC_CLOSE_MESSAGE =
            "I could not put together a complete plan for that order, so I am setting it aside.";

    private PlannerDispatch() {
    }

    public static void request(CompanionEntity companion, Planner planner, PlanRequest request) {
        MinecraftServer server = companion.getServer();
        ConversationCoordinator coordinator = companion.getConversationCoordinator();
        if (server == null || companion.getPlanExecutor() == null || coordinator == null) {
            return;
        }
        // Claimed now, on the server thread, so a later request or a cancel can disown this one.
        ConversationCoordinator.RequestTicket ticket = coordinator.begin(request.origin());
        planner.plan(request).whenComplete((response, error) ->
                server.execute(() -> apply(companion, planner, request, response, error, ticket)));
    }

    private static void apply(CompanionEntity companion, Planner planner, PlanRequest request,
            CompanionResponse response, Throwable error,
            ConversationCoordinator.RequestTicket ticket) {
        if (companion.isRemoved() || companion.getPlanExecutor() == null) {
            return;
        }
        ConversationCoordinator coordinator = companion.getConversationCoordinator();
        // Even a stale reply frees the in-flight slot; the queued turn behind it needs that.
        coordinator.completeInFlight(ticket);
        if (!coordinator.isCurrent(ticket)) {
            // The owner cancelled, stopped, or asked for something else while this was in flight.
            // Silently dropped: they already got feedback from whatever superseded it.
            MineMate.LOGGER.debug("Discarding stale response from planner {}", planner.name());
            return;
        }
        if (error != null || response == null) {
            String detail = error == null ? "no response produced" : rootMessage(error);
            MineMate.LOGGER.warn("Planner {} failed: {}", planner.name(), detail);
            if (companion.getContext() != null) {
                companion.getContext().record(EventKind.PLANNER_FAILED,
                        "planning failed (" + detail + ")", planner.name());
            }
            CompanionSpeech.send(companion, ToolMessages.owner(Reasons.PLANNER_UNAVAILABLE, ""));
            companion.closeStandingOrder();
            return;
        }
        if (response.close() != null) {
            applyClose(companion, planner, request, response);
            return;
        }
        if (response.isConversation()) {
            applyConversation(companion, planner, request, response);
            return;
        }
        if (planner.requiresContract()) {
            StandingOrder order = companion.getStandingOrder();
            if (order != null) {
                String violation = null;
                if (response.actions().isEmpty()) {
                    if (response.contract() != null && order.contract() == null) {
                        // The single-call habit: the model declares the contract and stops. Live
                        // sessions show a repair prompt often just reproduces the habit, so do not
                        // spend the repair on it. The contract itself is honest about what the
                        // order needs: record it and ask for the world calls as a continuation.
                        // Coverage is still enforced on that follow-up via awaitingCoverage.
                        order.declareContract(response.contract());
                        companion.resolveOwner().ifPresent(order.ledger()::captureOwnerBaselines);
                        order.markAwaitingCoverage();
                        continueForCoverage(companion, planner, request, response);
                        return;
                    }
                    violation = "the reply declared a contract but no world-tool calls";
                } else if (order.contract() == null) {
                    // First accepted action reply not seen yet: this reply must declare the
                    // contract, and its own actions must honor it.
                    violation = response.contract() == null
                            ? "the action reply did not declare the order contract"
                            : ContractCoverage.violation(response.contract(), stepsOf(response));
                } else if (order.awaitingCoverage()) {
                    // The stored contract arrived alone and nothing has executed since, so this
                    // first real plan is judged against the whole contract, exactly as if the
                    // model had sent contract and calls together.
                    violation = ContractCoverage.violation(order.contract(), stepsOf(response));
                } else {
                    if (response.contract() != null) {
                        // A replan re-declaring the contract is tolerated and ignored: the stored
                        // one is authoritative.
                        MineMate.LOGGER.debug(
                                "Planner {} re-declared the order contract on a replan",
                                planner.name());
                    }
                    // Replans plan only the remaining work, which the ledger's delivery evidence
                    // can now judge. Scoped to plan-finished replans: a damage-taken replan
                    // legitimately answers a threat with non-fetch work.
                    if (request.trigger() == ReplanReason.PLAN_FINISHED
                            && order.ledger() != null) {
                        companion.resolveOwner().ifPresent(order.ledger()::refreshFromOwner);
                        violation = ContractCoverage.remainderViolation(order.ledger(),
                                stepsOf(response));
                    }
                }
                if (violation != null) {
                    repairOrClose(companion, planner, request, response, violation);
                    return;
                }
                if (order.contract() == null) {
                    order.declareContract(response.contract());
                    companion.resolveOwner().ifPresent(order.ledger()::captureOwnerBaselines);
                }
                order.clearAwaitingCoverage();
            }
        }
        // Only now, with every semantic gate passed, does anything executable exist.
        Plan plan = new Plan(Plan.nextId(), request.requestText(), stepsOf(response));
        PlanExecutor.StartResult result = companion.getPlanExecutor().start(plan);
        switch (result) {
            case ACCEPTED -> {
                if (!response.speech().isBlank()) {
                    CompanionSpeech.send(companion, response.speech());
                }
            }
            case BUSY -> CompanionSpeech.send(companion, "I am already working on a plan");
            case REJECTED -> CompanionSpeech.send(companion, companion.getPlanExecutor().lastOutcome()
                    .map(outcome -> ToolMessages.owner(outcome.reason(), ""))
                    .orElse("I could not use that plan"));
        }
    }

    /** Close the standing order, with COMPLETED resting on ledger evidence rather than a claim. */
    private static void applyClose(CompanionEntity companion, Planner planner, PlanRequest request,
            CompanionResponse response) {
        OrderClose close = response.close();
        StandingOrder order = companion.getStandingOrder();
        if (order == null) {
            // A close for an order that no longer exists degrades to conversation: deliver the
            // words, log the stray status at debug, mutate nothing.
            MineMate.LOGGER.debug("Planner {} closed a non-existent order ({})", planner.name(),
                    close.status());
            CompanionSpeech.send(companion, displayLineOf(response));
            return;
        }
        if (close.status() == OrderCloseStatus.COMPLETED && order.contract() != null
                && order.contract().deliverToOwner() && order.ledger() != null) {
            // COMPLETED must rest on evidence: top the ledger up from the owner's live
            // inventory first, so items still in the air when the goal ended get credited.
            companion.resolveOwner().ifPresent(order.ledger()::refreshFromOwner);
            String undelivered = order.ledger().undeliveredSummary();
            if (undelivered != null) {
                repairOrClose(companion, planner, request, response,
                        "the reply claimed COMPLETED but the ledger shows " + undelivered,
                        "I could not confirm delivery: " + undelivered
                                + ". I am setting that order aside.");
                return;
            }
        }
        if (close.status() == OrderCloseStatus.CANCELLED
                && companion.getPlanExecutor().isActive()) {
            // Unreachable through today's request paths, which never leave a plan running while a
            // close can arrive; defensive so a cancellation can never close the order out from
            // under a still-executing plan.
            companion.getPlanExecutor().cancel(Reasons.CANCELLED_BY_OWNER);
        }
        CompanionSpeech.send(companion, displayLineOf(response));
        companion.closeStandingOrder();
    }

    /**
     * Speech with no actions and no close. For owner speech the order opened ahead of the reply
     * was provisional: the reply proves the utterance was conversation, so the order is discarded
     * without ceremony -- no failure event, no close status -- and autonomy cannot replan a chat.
     * For a replan, continuation, or repair, the request demanded work or a close, so small talk
     * is a violation and goes through the bounded repair.
     */
    private static void applyConversation(CompanionEntity companion, Planner planner,
            PlanRequest request, CompanionResponse response) {
        if (request.origin() == RequestOrigin.OWNER_CHAT) {
            CompanionSpeech.send(companion, response.speech());
            companion.closeStandingOrder();
            return;
        }
        repairOrClose(companion, planner, request, response,
                "the reply for the open order contained neither world-tool calls nor an order status");
    }

    /** The one displayed line for a close: the reply's speech, else the status fallback. */
    private static String displayLineOf(CompanionResponse response) {
        return response.speech().isBlank()
                ? response.close().fallbackMessage()
                : response.speech();
    }

    private static List<PlanStep> stepsOf(CompanionResponse response) {
        List<PlanStep> steps = new ArrayList<>(response.actions().size());
        for (ToolCall call : response.actions()) {
            steps.add(PlanStep.of(call));
        }
        return steps;
    }

    /**
     * The free follow-up for a contract-only first reply: the declared contract is stored, the
     * model's speech reaches the owner, and one more request asks for the world-tool calls. Not
     * charged against the repair, which stays reserved for a plan that violates the contract; a
     * model that sends the contract alone again after this goes through the ordinary repair path
     * and then closes.
     */
    private static void continueForCoverage(CompanionEntity companion, Planner planner,
            PlanRequest request, CompanionResponse response) {
        MineMate.LOGGER.info(
                "Planner {} declared the order contract without world calls; requesting the work",
                planner.name());
        if (!response.speech().isBlank()) {
            CompanionSpeech.send(companion, response.speech());
        }
        String note = "it declared the order contract, which is now recorded, but contained no"
                + " world-tool calls";
        WorldSnapshot snapshot = companion.getContext().snapshot();
        request(companion, planner, new PlanRequest(request.requestText(), request.companionName(),
                RequestOrigin.CONTRACT_CONTINUATION, snapshot, snapshot.render(),
                request.trigger(), request.toolSchemaJson(), note, ledgerTextOf(companion),
                PlannerRequests.transcriptOf(companion),
                PlannerRequests.ownerNameOf(companion)));
    }

    /**
     * One bounded repair per order: quote the violation and the rejected reply back to the model,
     * or close the order once that chance is spent. The rejected plan never reaches the executor
     * either way, so a semantically incomplete plan cannot half-execute.
     */
    private static void repairOrClose(CompanionEntity companion, Planner planner,
            PlanRequest request, CompanionResponse response, String violation) {
        repairOrClose(companion, planner, request, response, violation, SEMANTIC_CLOSE_MESSAGE);
    }

    private static void repairOrClose(CompanionEntity companion, Planner planner,
            PlanRequest request, CompanionResponse response, String violation,
            String closeMessage) {
        StandingOrder order = companion.getStandingOrder();
        if (order == null || !order.repairAvailable()) {
            MineMate.LOGGER.warn("Planner {} reply rejected after repair: {}", planner.name(),
                    violation);
            if (companion.getContext() != null) {
                companion.getContext().record(EventKind.PLANNER_FAILED,
                        "planning failed (" + violation + ")", planner.name());
            }
            CompanionSpeech.send(companion, closeMessage);
            companion.closeStandingOrder();
            return;
        }
        order.consumeRepair();
        MineMate.LOGGER.info("Planner {} reply rejected, requesting repair: {}", planner.name(),
                violation);
        String note = violation + "; the rejected reply said \"" + response.speech()
                + "\" and called " + response.actions().stream()
                        .map(ToolCall::toCompactString).toList();
        // A fresh snapshot: seconds passed during the round trip, and snapshots memoize per tick.
        // Requesting synchronously on this same server-thread slice re-raises the pending flag
        // before PlannerAutonomy can interleave, and /companion stop still disowns the repair
        // through the ordinary generation counter.
        WorldSnapshot snapshot = companion.getContext().snapshot();
        request(companion, planner, new PlanRequest(request.requestText(), request.companionName(),
                RequestOrigin.REPAIR, snapshot, snapshot.render(), request.trigger(),
                request.toolSchemaJson(), note, ledgerTextOf(companion),
                PlannerRequests.transcriptOf(companion),
                PlannerRequests.ownerNameOf(companion)));
    }

    /** The standing order's ledger rendered for the prompt, refreshed against the live owner. */
    static String ledgerTextOf(CompanionEntity companion) {
        StandingOrder order = companion.getStandingOrder();
        if (order == null || order.ledger() == null) {
            return null;
        }
        companion.resolveOwner().ifPresent(order.ledger()::refreshFromOwner);
        return order.ledger().describe();
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
