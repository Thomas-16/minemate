package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.MineMate;
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
     * decided against this, never against a bare number a closure remembered.
     */
    public record RequestTicket(int generation, RequestOrigin origin) {
        public RequestTicket {
            Objects.requireNonNull(origin, "origin");
        }
    }

    private record DeferredReplan(ReplanTrigger trigger, StandingOrder order) {
    }

    /**
     * Bound on the coalesced turn waiting behind an in-flight request. Oldest lines are evicted
     * first: the newest correction is the operative text, not the opening remark.
     */
    private static final int MAX_QUEUED_TURN_CHARS = 2000;

    private final CompanionEntity companion;

    private int generation;
    private RequestTicket inFlight;
    private final ArrayDeque<String> queuedLines = new ArrayDeque<>();
    private int queuedChars;
    private final ArrayDeque<String> debounceLines = new ArrayDeque<>();
    private long debounceDeadlineGameTime = -1L;
    private DeferredReplan deferredReplan;

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
        inFlight = new RequestTicket(++generation, origin);
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
