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
 *
 * <p>Two chains run through here with different rules. The <em>owner chain</em> (a fresh owner
 * utterance plus the continuation and repair requests of that one response attempt) opens a
 * standing order only at acceptance, keeps its bounded repair request-local, and on any failure
 * preserves whatever unrelated work is running. The <em>autonomous chain</em> (a replan and its
 * follow-ups) continues the order that was open when its request began and keeps the
 * order-scoped budget discipline.
 */
public final class PlannerDispatch {

    /** Owner-facing close message when a reply stays semantically incomplete after its repair. */
    public static final String SEMANTIC_CLOSE_MESSAGE =
            "I could not put together a complete plan for that order, so I am setting it aside.";

    /** Owner-facing line when a fresh response attempt fails; no order ever existed for it. */
    public static final String ATTEMPT_FAILED_MESSAGE =
            "I could not put together the steps for that. Ask me again if you still want it.";

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
            applyFailure(companion, planner, request, ticket,
                    error == null ? "no response produced" : rootMessage(error));
            return;
        }
        if (ticket.origin() != RequestOrigin.OWNER_CHAT
                && !ownerChain(ticket.origin(), request)
                && orderEpochOf(companion) != ticket.orderEpochAtStart()) {
            // A replan or its follow-up continues the order that was open when its request began.
            // That order has since closed or been replaced, so this result no longer addresses
            // anything; replaying it is forbidden (design section 7.2).
            MineMate.LOGGER.debug("Discarding response for a closed or replaced order from {}",
                    planner.name());
            return;
        }
        if (response.close() != null) {
            applyClose(companion, planner, request, response, ticket);
            return;
        }
        if (response.isConversation()) {
            applyConversation(companion, planner, request, response, ticket);
            return;
        }
        if (ownerChain(ticket.origin(), request)) {
            applyOwnerAction(companion, planner, request, response);
            return;
        }
        applyAutonomousAction(companion, planner, request, response);
    }

    /**
     * The owner chain is the fresh utterance and the continuation or repair of that one response
     * attempt; everything spawned from a replan trigger carries the trigger and stays
     * order-scoped.
     */
    private static boolean ownerChain(RequestOrigin origin, PlanRequest request) {
        return origin == RequestOrigin.OWNER_CHAT
                || (origin != RequestOrigin.AUTONOMOUS_REPLAN && request.trigger() == null);
    }

    /**
     * Transport error, timeout, or no response at all. An owner-chain failure produces one short
     * fallback and preserves every piece of unrelated state -- the order, the running plan, and a
     * deferred replan all survive (design section 7.1). An autonomous failure keeps the bounded
     * order behavior: the failure is recorded and the order closes rather than idling forever.
     */
    private static void applyFailure(CompanionEntity companion, Planner planner,
            PlanRequest request, ConversationCoordinator.RequestTicket ticket, String detail) {
        MineMate.LOGGER.warn("Planner {} failed: {}", planner.name(), detail);
        if (companion.getContext() != null) {
            companion.getContext().record(EventKind.PLANNER_FAILED,
                    "planning failed (" + detail + ")", planner.name());
        }
        String fallback = ToolMessages.owner(Reasons.PLANNER_UNAVAILABLE, "");
        if (ownerChain(ticket.origin(), request)) {
            StandingOrder order = companion.getStandingOrder();
            if (companion.getPlanExecutor().isActive() && order != null) {
                fallback = fallback + " I am still working on: " + order.requestText() + ".";
            }
            CompanionSpeech.send(companion, fallback);
            companion.getConversationCoordinator().clearAttemptState();
            return;
        }
        CompanionSpeech.send(companion, fallback);
        companion.closeStandingOrder();
    }

    /**
     * An owner-chain response carrying actions, a contract, or both. No standing order exists for
     * it yet: the contract-only habit is absorbed into a request-local {@link
     * ConversationCoordinator.PendingCommitment}, validation failures spend the request-local
     * repair, and only full acceptance performs the atomic order-and-plan transition.
     */
    private static void applyOwnerAction(CompanionEntity companion, Planner planner,
            PlanRequest request, CompanionResponse response) {
        ConversationCoordinator coordinator = companion.getConversationCoordinator();
        PlanExecutor executor = companion.getPlanExecutor();
        ConversationCoordinator.PendingCommitment commitment = coordinator.pendingCommitment();
        // A repair reply that redeclares the contract is validated as a whole, self-contained;
        // otherwise the stored commitment's contract is what the actions must cover.
        OrderContract contract = response.contract() != null ? response.contract()
                : commitment == null ? null : commitment.contract();

        if (planner.requiresContract()) {
            if (response.actions().isEmpty()) {
                if (commitment == null && response.contract() != null) {
                    // The single-call habit: the model declares the contract and stops. Live
                    // sessions show a repair prompt often just reproduces the habit, so do not
                    // spend the repair on it. Store the commitment -- no order, no ledger, no
                    // speech yet -- and ask for the world calls as a free continuation.
                    coordinator.storePendingCommitment(
                            new ConversationCoordinator.PendingCommitment(response.contract(),
                                    response.speech(), request.requestText()));
                    continueAttemptForCoverage(companion, planner, request);
                    return;
                }
                repairAttemptOrFail(companion, planner, request, response,
                        "the reply declared a contract but no world-tool calls");
                return;
            }
            if (contract == null) {
                repairAttemptOrFail(companion, planner, request, response,
                        "the action reply did not declare the order contract");
                return;
            }
        }
        Plan plan = new Plan(Plan.nextId(), request.requestText(), stepsOf(response));
        PlanExecutor.CoverageScope scope = planner.requiresContract()
                ? PlanExecutor.CoverageScope.FULL : PlanExecutor.CoverageScope.NONE;
        switch (executor.prepare(plan, planner.requiresContract() ? contract : null, scope,
                null)) {
            case PlanExecutor.PrepareOutcome.Invalid invalid ->
                    repairAttemptOrFail(companion, planner, request, response, invalid.reason());
            case PlanExecutor.PrepareOutcome.Ready ready -> {
                // Nothing was cancelled or closed until this point; the replacement is ready, so
                // the transition is one server-thread step and cannot strand the companion.
                executor.startOwnerAction(ready.prepared(), request.requestText());
                if (commitment != null && !commitment.bufferedSpeech().isBlank()) {
                    CompanionSpeech.send(companion, commitment.bufferedSpeech());
                }
                if (!response.speech().isBlank()) {
                    CompanionSpeech.send(companion, response.speech());
                }
                coordinator.clearAttemptState();
            }
        }
    }

    /** The autonomous chain: the standing order in place when the request began is authoritative. */
    private static void applyAutonomousAction(CompanionEntity companion, Planner planner,
            PlanRequest request, CompanionResponse response) {
        if (planner.requiresContract()) {
            StandingOrder order = companion.getStandingOrder();
            if (order != null) {
                String violation = null;
                if (response.actions().isEmpty()) {
                    if (response.contract() != null && order.contract() == null) {
                        // The single-call habit on a replan whose order has no contract yet: the
                        // contract itself is honest about what the order needs, so record it and
                        // ask for the world calls as a continuation. Coverage is still enforced
                        // on that follow-up via awaitingCoverage.
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
            // Unreachable through today's request paths -- autonomy refuses while a plan is
            // active -- and an autonomous plan must never steamroll newer work, so drop it.
            case BUSY -> MineMate.LOGGER.debug(
                    "Planner {} autonomous plan arrived while another plan runs; dropped",
                    planner.name());
            case REJECTED -> CompanionSpeech.send(companion, companion.getPlanExecutor()
                    .lastOutcome()
                    .map(outcome -> ToolMessages.owner(outcome.reason(), ""))
                    .orElse("I could not use that plan"));
        }
    }

    /** Close the standing order, with COMPLETED resting on ledger evidence rather than a claim. */
    private static void applyClose(CompanionEntity companion, Planner planner, PlanRequest request,
            CompanionResponse response, ConversationCoordinator.RequestTicket ticket) {
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
        if (order.epoch() != ticket.orderEpochAtStart()) {
            // The order this close was composed against is gone; the one now open is someone
            // else's business. Stale, ignored, nothing delivered (design section 2).
            MineMate.LOGGER.debug("Planner {} closed a superseded order ({})", planner.name(),
                    close.status());
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
        if (companion.getPlanExecutor().isActive()) {
            // No accepted close may leave a plan running (design section 2). CANCELLED is the
            // owner changing their mind; any other status still ends the work the plan served.
            // Cancelling harvests the goal's delivery evidence into the ledger on the way out.
            companion.getPlanExecutor().cancel(close.status() == OrderCloseStatus.CANCELLED
                    ? Reasons.CANCELLED_BY_OWNER : Reasons.SUPERSEDED);
        }
        CompanionSpeech.send(companion, displayLineOf(response));
        companion.closeStandingOrder();
        companion.getConversationCoordinator().clearAttemptState();
    }

    /**
     * Speech with no actions and no close. A fresh owner utterance answered with speech is simply
     * conversation: nothing opens, and -- unlike before stage 5 -- nothing closes either, so a
     * status question mid-plan preserves plan, order, contract, and ledger identity. Speech-only
     * to the owner attempt's continuation or repair ends the attempt conversationally: the speech
     * is the message, and unrelated work is untouched. For the autonomous chain, the request
     * demanded work or a close, so small talk is a violation and goes through the bounded repair.
     */
    private static void applyConversation(CompanionEntity companion, Planner planner,
            PlanRequest request, CompanionResponse response,
            ConversationCoordinator.RequestTicket ticket) {
        if (ticket.origin() == RequestOrigin.OWNER_CHAT) {
            CompanionSpeech.send(companion, response.speech());
            return;
        }
        if (ownerChain(ticket.origin(), request)) {
            CompanionSpeech.send(companion, response.speech());
            companion.getConversationCoordinator().clearAttemptState();
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
     * The free follow-up for an owner attempt's contract-only first reply. Unlike the autonomous
     * twin below, nothing is spoken and no order is touched: the commitment is request-local, and
     * the buffered speech is delivered only if covering calls are accepted, so the companion
     * never promises work it has not committed to.
     */
    private static void continueAttemptForCoverage(CompanionEntity companion, Planner planner,
            PlanRequest request) {
        MineMate.LOGGER.info(
                "Planner {} declared the contract without world calls; requesting the work",
                planner.name());
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
     * The free follow-up for an autonomous contract-only reply: the declared contract is stored
     * on the order, the model's speech reaches the owner, and one more request asks for the
     * world-tool calls. Not charged against the repair, which stays reserved for a plan that
     * violates the contract.
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
     * The owner attempt's bounded repair: one request-local retry, then a conversational failure
     * that names what survives. No order exists for the attempt, so nothing closes; a plan the
     * attempt was trying to replace keeps running and the fallback says so (design section 7.1).
     */
    private static void repairAttemptOrFail(CompanionEntity companion, Planner planner,
            PlanRequest request, CompanionResponse response, String violation) {
        ConversationCoordinator coordinator = companion.getConversationCoordinator();
        if (!coordinator.attemptRepairAvailable()) {
            MineMate.LOGGER.warn("Planner {} reply rejected after repair: {}", planner.name(),
                    violation);
            if (companion.getContext() != null) {
                companion.getContext().record(EventKind.PLANNER_FAILED,
                        "planning failed (" + violation + ")", planner.name());
            }
            String message = ATTEMPT_FAILED_MESSAGE;
            StandingOrder order = companion.getStandingOrder();
            if (companion.getPlanExecutor().isActive() && order != null) {
                message = "I could not work out how to switch to that. I am still working on: "
                        + order.requestText() + ".";
            }
            CompanionSpeech.send(companion, message);
            coordinator.clearAttemptState();
            return;
        }
        coordinator.consumeAttemptRepair();
        MineMate.LOGGER.info("Planner {} reply rejected, requesting repair: {}", planner.name(),
                violation);
        String note = violation + "; the rejected reply said \"" + response.speech()
                + "\" and called " + response.actions().stream()
                        .map(ToolCall::toCompactString).toList();
        // A fresh snapshot: seconds passed during the round trip, and snapshots memoize per tick.
        // Requesting synchronously on this same server-thread slice claims the next generation
        // before PlannerAutonomy or a queued turn can interleave.
        WorldSnapshot snapshot = companion.getContext().snapshot();
        request(companion, planner, new PlanRequest(request.requestText(), request.companionName(),
                RequestOrigin.REPAIR, snapshot, snapshot.render(), request.trigger(),
                request.toolSchemaJson(), note, ledgerTextOf(companion),
                PlannerRequests.transcriptOf(companion),
                PlannerRequests.ownerNameOf(companion)));
    }

    /**
     * The autonomous chain's bounded repair, charged against the standing order: quote the
     * violation and the rejected reply back to the model, or close the order once that chance is
     * spent. The rejected plan never reaches the executor either way, so a semantically
     * incomplete plan cannot half-execute.
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
        // Requesting synchronously on this same server-thread slice claims the next generation
        // before PlannerAutonomy can interleave, and /companion stop still disowns the repair
        // through the ordinary generation counter.
        WorldSnapshot snapshot = companion.getContext().snapshot();
        request(companion, planner, new PlanRequest(request.requestText(), request.companionName(),
                RequestOrigin.REPAIR, snapshot, snapshot.render(), request.trigger(),
                request.toolSchemaJson(), note, ledgerTextOf(companion),
                PlannerRequests.transcriptOf(companion),
                PlannerRequests.ownerNameOf(companion)));
    }

    /** The epoch of the currently open order, or 0 with none open. */
    private static long orderEpochOf(CompanionEntity companion) {
        StandingOrder order = companion.getStandingOrder();
        return order == null ? 0L : order.epoch();
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
