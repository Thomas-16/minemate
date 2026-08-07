package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.chat.CompanionSpeech;
import io.github.thomas16.minemate.context.ReplanTrigger;
import io.github.thomas16.minemate.entity.CompanionEntity;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Server-thread-only owner of one companion's model-request concurrency: at most one hosted
 * request is in flight, newer owner text stales an in-flight result, owner turns queue and
 * coalesce while a request is out, and a replan trigger that fires during owner chat is retained
 * instead of dropped. {@link PlanExecutor} runs plans; it no longer owns conversation
 * generations.
 *
 * <p>Staleness is a single monotonic generation: every {@link #begin} claims the next one, so
 * whatever was in flight before is disowned the moment anything newer is asked for. A stale
 * completion still clears the in-flight slot ({@link #completeInFlight} matches the ticket, not
 * the generation), which is what lets the queued turn dispatch afterwards -- and
 * {@code HostedPlanner}'s own timeout guarantees that completion always arrives, so the slot can
 * never wedge shut.
 */
public final class ConversationCoordinator {

    /**
     * Identity a completion carries back to the server thread; staleness and applicability are
     * decided against this, never against a bare number a closure remembered. The order epoch and
     * plan id are captured when the request begins, so dispatch can tell whether a result still
     * addresses the world it was asked about ({@code 0} / {@code ""} when none was open).
     */
    public record RequestTicket(int generation, RequestOrigin origin, long orderEpochAtStart,
            String planIdAtStart) {
        public RequestTicket {
            Objects.requireNonNull(origin, "origin");
            planIdAtStart = planIdAtStart == null ? "" : planIdAtStart;
        }
    }

    private record DeferredReplan(ReplanTrigger trigger, StandingOrder order) {
    }

    /**
     * Request-local state for one owner response attempt whose first reply declared a contract
     * without world calls. Nothing outside the attempt exists yet: no order, no ledger, and the
     * reply's speech stays buffered until covering actions are accepted, so the companion never
     * promises work it has not committed to (design section 5.4).
     */
    public record PendingCommitment(OrderContract contract, String bufferedSpeech,
            String ownerTurnText) {
        public PendingCommitment {
            Objects.requireNonNull(contract, "contract");
            bufferedSpeech = bufferedSpeech == null ? "" : bufferedSpeech;
            ownerTurnText = ownerTurnText == null ? "" : ownerTurnText;
        }
    }

    /**
     * Bound on the coalesced turn waiting behind an in-flight request. Oldest lines are evicted
     * first: the newest correction is the operative text, not the opening remark.
     */
    private static final int MAX_QUEUED_TURN_CHARS = 2000;

    /** One rolling minute, in game ticks, for the chat request cap. */
    private static final long CAP_WINDOW_TICKS = 1200L;

    /** Spoken once per saturation of the rolling cap; ASCII, like all generated player text. */
    private static final String THROTTLE_MESSAGE =
            "Give me a moment - I can only keep up with so much chatter at once.";

    private final CompanionEntity companion;

    private int generation;
    private RequestTicket inFlight;
    private final ArrayDeque<String> queuedLines = new ArrayDeque<>();
    private int queuedChars;
    private final ArrayDeque<String> debounceLines = new ArrayDeque<>();
    private long debounceDeadlineGameTime = -1L;
    private DeferredReplan deferredReplan;
    private PendingCommitment pendingCommitment;
    private boolean attemptRepairUsed;
    private final ArrayDeque<Long> hostedChatTurnTicks = new ArrayDeque<>();
    private boolean throttleNotified;
    /** False once a command turn joins the pending turn; commands never throttle. */
    private boolean pendingTurnFromChatOnly = true;

    public ConversationCoordinator(CompanionEntity companion) {
        this.companion = Objects.requireNonNull(companion, "companion");
    }

    // ---- owner input ------------------------------------------------------------------------

    /**
     * A complete owner turn with no debounce: {@code /companion plan} and the headless harness.
     * A command argument is one utterance by construction; only free typing debounces.
     */
    public void submitOwnerTurnNow(String text) {
        assertServerThread();
        if (text == null || text.isBlank()) {
            return;
        }
        pendingTurnFromChatOnly = false;
        if (inFlight != null) {
            // The in-flight reply answers an utterance the owner has already moved past.
            generation++;
            enqueue(text.strip());
            return;
        }
        if (!queuedLines.isEmpty() || !debounceLines.isEmpty()) {
            // Something older is already waiting; joining it keeps the turns in spoken order.
            enqueue(text.strip());
            return;
        }
        dispatch(text.strip());
        pendingTurnFromChatOnly = true;
    }

    /**
     * A line of owner chat. Debounced: further lines within the window join the same logical
     * turn, so a thought typed as three messages costs one hosted request, not three.
     */
    public void submitOwnerTurn(String text) {
        assertServerThread();
        if (text == null || text.isBlank()) {
            return;
        }
        debounceLines.addLast(text.strip());
        long debounceTicks = Math.max(1L,
                (MineMate.plannerConfig().chatDebounceMs() + 49L) / 50L);
        debounceDeadlineGameTime = companion.level().getGameTime() + debounceTicks;
    }

    // ---- request lifecycle (PlannerDispatch only) --------------------------------------------

    /** Claims the next request slot. Whatever was in flight before is stale from this moment. */
    public RequestTicket begin(RequestOrigin origin) {
        assertServerThread();
        if (origin == RequestOrigin.OWNER_CHAT || origin == RequestOrigin.AUTONOMOUS_REPLAN) {
            // A fresh attempt; whatever a previous attempt buffered or spent dies with it. The
            // continuation and repair origins belong to the current attempt and keep its state.
            clearAttemptState();
        }
        StandingOrder order = companion.getStandingOrder();
        long orderEpoch = order == null ? 0L : order.epoch();
        String planId = companion.getPlanExecutor() == null ? ""
                : companion.getPlanExecutor().currentPlan().map(Plan::id).orElse("");
        inFlight = new RequestTicket(++generation, origin, orderEpoch, planId);
        return inFlight;
    }

    /** Whether a completed request is still the one the conversation is waiting for. */
    public boolean isCurrent(RequestTicket ticket) {
        return ticket != null && ticket.generation() == generation;
    }

    /**
     * Marks the request answered, whatever the answer was. Deliberately matches the ticket rather
     * than the current generation: a stale reply must still free the slot, or the queued turn
     * behind it could never dispatch.
     */
    public void completeInFlight(RequestTicket ticket) {
        assertServerThread();
        if (inFlight == ticket) {
            inFlight = null;
        }
    }

    // ---- queries ----------------------------------------------------------------------------

    /** Whether a planner has been asked for a response that has not come back yet. */
    public boolean isRequestPending() {
        return inFlight != null;
    }

    /** Whether owner conversation is pending anywhere: in flight, queued, or still being typed. */
    public boolean isOwnerTurnPending() {
        return (inFlight != null && inFlight.origin() == RequestOrigin.OWNER_CHAT)
                || !queuedLines.isEmpty()
                || !debounceLines.isEmpty();
    }

    /** Autonomy may only speak up when the conversation is fully idle; owner chat outranks it. */
    public boolean mayStartAutonomousRequest() {
        return inFlight == null && queuedLines.isEmpty() && debounceLines.isEmpty();
    }

    // ---- pending commitment (request-local, stage 5) ----------------------------------------

    /** The current attempt's stored contract-only commitment, or null. */
    public PendingCommitment pendingCommitment() {
        return pendingCommitment;
    }

    public void storePendingCommitment(PendingCommitment commitment) {
        assertServerThread();
        pendingCommitment = commitment;
    }

    /** One bounded semantic repair per owner response attempt, request-local until acceptance. */
    public boolean attemptRepairAvailable() {
        return !attemptRepairUsed;
    }

    public void consumeAttemptRepair() {
        assertServerThread();
        attemptRepairUsed = true;
    }

    /** Ends the current owner response attempt: accepted, failed, or superseded, it is over. */
    public void clearAttemptState() {
        pendingCommitment = null;
        attemptRepairUsed = false;
    }

    /**
     * Test seam: forgets rate-limiter history. The window is real time an owner lives through,
     * but consecutive headless scenarios compress hours of play into seconds and must not leak
     * charges into each other.
     */
    public void resetChatCap() {
        hostedChatTurnTicks.clear();
        throttleNotified = false;
    }

    // ---- deferred replans -------------------------------------------------------------------

    /**
     * Retains a replan trigger that fired while owner chat was pending; one slot, newest wins.
     * Reconsidered by {@link #tick} once the conversation is idle, and only while the same order
     * is still open -- a chat that closed or replaced the order ends the deferral with it.
     */
    public void deferReplan(ReplanTrigger trigger, StandingOrder order) {
        assertServerThread();
        deferredReplan = new DeferredReplan(trigger, order);
    }

    // ---- invalidation -----------------------------------------------------------------------

    /**
     * Stop means stop: disowns the in-flight request, drops queued and half-typed owner turns,
     * and forgets any deferred replan. The in-flight slot itself is kept -- the late completion
     * clears it on arrival -- so "one request in flight" stays true even across a stop.
     */
    public void invalidateAll() {
        assertServerThread();
        generation++;
        queuedLines.clear();
        queuedChars = 0;
        debounceLines.clear();
        debounceDeadlineGameTime = -1L;
        deferredReplan = null;
        clearAttemptState();
    }

    // ---- tick -------------------------------------------------------------------------------

    /**
     * Runs after {@code context.tick()} so this slice's replan triggers have already been heard
     * (and refused or deferred) before a queued owner turn or deferred replan dispatches.
     */
    public void tick() {
        assertServerThread();
        if (companion.isRemoved()) {
            return;
        }
        long gameTime = companion.level().getGameTime();
        if (!debounceLines.isEmpty() && gameTime >= debounceDeadlineGameTime) {
            String turn = String.join("\n", debounceLines);
            debounceLines.clear();
            debounceDeadlineGameTime = -1L;
            if (inFlight != null) {
                // The typed correction outranks whatever the model is still composing.
                generation++;
            }
            enqueue(turn);
        }
        if (inFlight == null && !queuedLines.isEmpty()) {
            String turn = String.join("\n", queuedLines);
            queuedLines.clear();
            queuedChars = 0;
            boolean fromChatOnly = pendingTurnFromChatOnly;
            pendingTurnFromChatOnly = true;
            if (fromChatOnly && !tryChargeChatTurn(gameTime)) {
                // Over the rolling cap: the words are still heard and remembered -- they become
                // history for the next hosted request -- but this turn is never auto-submitted.
                if (companion.getConversationLog() != null) {
                    companion.getConversationLog().recordOwner(turn, gameTime);
                }
                if (!throttleNotified) {
                    throttleNotified = true;
                    CompanionSpeech.send(companion, THROTTLE_MESSAGE);
                }
                return;
            }
            dispatch(turn);
            return;
        }
        if (inFlight == null && queuedLines.isEmpty() && debounceLines.isEmpty()
                && deferredReplan != null) {
            DeferredReplan deferred = deferredReplan;
            deferredReplan = null;
            if (companion.getStandingOrder() == deferred.order()) {
                // Directly, not through ReplanTriggers.fire: the trigger already paid its
                // cooldowns when it fired, and PlannerAutonomy re-runs every gate of its own.
                PlannerAutonomy.onReplanTrigger(companion, deferred.trigger());
            }
        }
    }

    /**
     * Charges the rolling per-minute chat cap, counting coalesced hosted turns rather than raw
     * chat lines. Command turns never pass through here: {@code /companion plan} stays the
     * deterministic fallback whatever the chatter level.
     */
    private boolean tryChargeChatTurn(long gameTime) {
        while (!hostedChatTurnTicks.isEmpty()
                && gameTime - hostedChatTurnTicks.peekFirst() >= CAP_WINDOW_TICKS) {
            hostedChatTurnTicks.removeFirst();
        }
        if (hostedChatTurnTicks.size() >= MineMate.plannerConfig().chatRequestsPerMinute()) {
            return false;
        }
        hostedChatTurnTicks.addLast(gameTime);
        throttleNotified = false;
        return true;
    }

    private void enqueue(String text) {
        queuedLines.addLast(text);
        queuedChars += text.length();
        while (queuedChars > MAX_QUEUED_TURN_CHARS && queuedLines.size() > 1) {
            queuedChars -= queuedLines.removeFirst().length();
        }
    }

    private void dispatch(String turnText) {
        PlannerRequests.owner(companion, Planners.active(), turnText);
    }

    private void assertServerThread() {
        assert companion.getServer() != null && companion.getServer().isSameThread();
    }
}
