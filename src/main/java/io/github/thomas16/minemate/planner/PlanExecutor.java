package io.github.thomas16.minemate.planner;

import com.google.gson.JsonElement;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.context.EventKind;
import io.github.thomas16.minemate.context.ReplanReason;
import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.task.DeliveryEvidence;
import io.github.thomas16.minemate.task.DeliveryEvidenceSource;
import io.github.thomas16.minemate.task.Goal;
import io.github.thomas16.minemate.task.OutcomeStatus;
import io.github.thomas16.minemate.task.Reasons;
import io.github.thomas16.minemate.task.TaskOutcome;
import io.github.thomas16.minemate.task.TaskSupervisor;
import io.github.thomas16.minemate.tool.ToolBindException;
import io.github.thomas16.minemate.tool.ToolContext;
import io.github.thomas16.minemate.tool.ToolRegistry;
import io.github.thomas16.minemate.tool.ToolValidation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs a plan one step at a time on top of the single-goal {@link TaskSupervisor}.
 *
 * <p>It never touches PlayerEngine. It owns exactly one decision — which goal should be running now
 * — and the supervisor remains the sole authority on whether that goal actually succeeded.
 *
 * <p><strong>Step completion is polled, not listened for.</strong> {@code TaskListeners} has no
 * unregister, so a per-companion listener would leak for every dismissed companion; and reacting
 * inside {@code fireFinished} would start the next step from within the previous step's terminal
 * callback, on a supervisor still mid-finish. Polling runs on the line after {@code supervisor.tick()},
 * sees the outcome it just published, and has no re-entrancy at all. A useful consequence: when the
 * owner issues a chat order mid-plan, the supervisor cancels our goal as {@code superseded} and the
 * ordinary outcome branch below ends the plan, with no special case anywhere.
 *
 * <p>Active plans are deliberately not persisted across an entity reload, matching the supervisor's
 * stage-2 choice for goals. A half-executed plan built against hours-old world assumptions is worse
 * than no plan.
 */
public final class PlanExecutor {

    public enum StartResult { ACCEPTED, BUSY, REJECTED }

    /** What contract discipline {@link #prepare} must hold a plan to. */
    public enum CoverageScope {
        /** No contract in play: structural validation only. */
        NONE,
        /** The whole declared contract must be covered, as on a first accepted action reply. */
        FULL,
        /** Only the ledger's undelivered remainder must be covered, as on a replan. */
        REMAINDER
    }

    /**
     * A plan whose every step passed validation, plus the contract it was validated against
     * (null when none was declared); the only thing {@link #commit} accepts.
     */
    public record PreparedPlan(Plan plan, List<ToolValidation.Accepted> steps,
            OrderContract contract) {
    }

    /** The result of pure validation: a committable plan or one reason it cannot run. */
    public sealed interface PrepareOutcome {
        record Ready(PreparedPlan prepared) implements PrepareOutcome {
        }

        record Invalid(String reason) implements PrepareOutcome {
        }
    }

    private final CompanionEntity companion;

    private Plan plan;
    private List<ToolValidation.Accepted> validatedSteps = List.of();
    private final List<PlanStepOutcome> stepOutcomes = new ArrayList<>();
    private int stepIndex;
    private String awaitingGoalId;
    private Goal activeGoal;
    private long startMs;
    private PlanOutcome lastOutcome;
    private boolean waitingForSupervisorHandoff;

    public PlanExecutor(CompanionEntity companion) {
        this.companion = Objects.requireNonNull(companion, "companion");
    }

    /**
     * Validates the whole plan, then accepts it. Nothing reaches the supervisor unless every step
     * passes: a plan that would die on step three is better refused before step one moves the
     * companion anywhere.
     */
    public StartResult start(Plan newPlan) {
        assertServerThread();
        if (newPlan == null) {
            return StartResult.REJECTED;
        }
        // BUSY before validation, exactly as before the prepare/commit split: an occupied
        // executor answers BUSY even for a plan that would not have validated.
        if (plan != null) {
            return StartResult.BUSY;
        }
        return switch (prepare(newPlan)) {
            case PrepareOutcome.Ready ready -> commit(ready.prepared());
            case PrepareOutcome.Invalid invalid -> {
                publish(newPlan, PlanStatus.REJECTED, invalid.reason(), List.of(), 0L);
                yield StartResult.REJECTED;
            }
        };
    }

    /** Structural validation only; see the four-argument overload. */
    public PrepareOutcome prepare(Plan newPlan) {
        return prepare(newPlan, null, CoverageScope.NONE, null);
    }

    /**
     * Pure validation: step bound, registry acceptance, continuous-tool placement, and contract
     * coverage at the requested scope. Mutates no live state, so a caller can prepare a
     * replacement while other work still runs. Dispatch stays the semantic gate that chooses the
     * scope and refreshes the ledger; the checking itself lives here, so {@link #commit} can only
     * ever receive a plan whose whole discipline passed.
     */
    public PrepareOutcome prepare(Plan newPlan, OrderContract contract, CoverageScope scope,
            OrderLedger remainderLedger) {
        assertServerThread();
        if (newPlan == null || newPlan.steps().isEmpty()) {
            return new PrepareOutcome.Invalid(Reasons.PLAN_EMPTY);
        }
        if (newPlan.steps().size() > Plan.MAX_STEPS) {
            return new PrepareOutcome.Invalid(Reasons.PLAN_TOO_LONG);
        }
        List<ToolValidation.Accepted> accepted = new ArrayList<>(newPlan.steps().size());
        for (int index = 0; index < newPlan.steps().size(); index++) {
            PlanStep step = newPlan.steps().get(index);
            ToolValidation validation = ToolRegistry.defaults().validate(step.call());
            if (validation instanceof ToolValidation.Rejected rejected) {
                return new PrepareOutcome.Invalid(rejected.reason());
            }
            ToolValidation.Accepted ok = (ToolValidation.Accepted) validation;
            // A continuous tool never finishes on its own, so anything after it would never run.
            if (ok.tool().continuous() && index != newPlan.steps().size() - 1) {
                return new PrepareOutcome.Invalid(Reasons.INVALID_ARGUMENT);
            }
            accepted.add(ok);
        }
        String coverageViolation = switch (scope) {
            case NONE -> null;
            case FULL -> ContractCoverage.violation(contract, newPlan.steps());
            case REMAINDER -> ContractCoverage.remainderViolation(remainderLedger,
                    newPlan.steps());
        };
        if (coverageViolation != null) {
            return new PrepareOutcome.Invalid(coverageViolation);
        }
        return new PrepareOutcome.Ready(new PreparedPlan(newPlan, List.copyOf(accepted),
                contract));
    }

    /**
     * One server-thread transition for an accepted owner action: replaces whatever is running.
     * Every validation already happened in {@link #prepare}, so nothing on this path can fail.
     * Order matters: the old plan's goal is cancelled and its evidence harvested into the old
     * ledger by {@code finish} before the order swap, because {@code harvestEvidence} reads the
     * live standing order; only then does the fresh order open with a fresh epoch and budget.
     */
    public StartResult startOwnerAction(PreparedPlan prepared, String ownerTurnText) {
        assertServerThread();
        if (plan != null) {
            if (awaitingGoalId != null && companion.getSupervisor().isBusy()) {
                companion.getSupervisor().cancel(Reasons.SUPERSEDED);
                // The cancelled goal takes a tick to leave the supervisor; the replacement's
                // first step must wait for that instead of being refused by submit.
                waitingForSupervisorHandoff = true;
            }
            // Publishes the superseded outcome and arms PLAN_FINISHED, which autonomy will
            // refuse because the replacement plan is active by then.
            finish(PlanStatus.CANCELLED, Reasons.SUPERSEDED);
        }
        companion.closeStandingOrder();
        companion.openStandingOrder(ownerTurnText);
        if (prepared.contract() != null) {
            StandingOrder order = companion.getStandingOrder();
            order.declareContract(prepared.contract());
            companion.resolveOwner().ifPresent(order.ledger()::captureOwnerBaselines);
        }
        return commit(prepared);
    }

    /** One server-thread transition installing an already-validated plan. */
    public StartResult commit(PreparedPlan prepared) {
        assertServerThread();
        if (plan != null) {
            return StartResult.BUSY;
        }
        plan = prepared.plan();
        validatedSteps = prepared.steps();
        stepOutcomes.clear();
        stepIndex = 0;
        awaitingGoalId = null;
        startMs = System.currentTimeMillis();
        if (companion.getContext() != null) {
            companion.getContext().record(EventKind.PLAN_STARTED,
                    "plan %s started (%d steps)".formatted(plan.id(), plan.steps().size()),
                    plan.requestText());
        }
        recordActivity("started: %s (%d steps)".formatted(plan.requestText(),
                plan.steps().size()));
        MineMate.LOGGER.info("Plan {} started with {} steps", plan.id(), plan.steps().size());
        return StartResult.ACCEPTED;
    }

    /**
     * A concise game-authored record for conversational memory: past actions reach the model as
     * trusted activity summaries, never as assistant-authored speech or raw tool calls. The
     * executor is the single source of these so the journal and the conversation cannot
     * double-record one event.
     */
    private void recordActivity(String summary) {
        if (companion.getConversationLog() != null) {
            companion.getConversationLog().recordActivity(summary, companion.level().getGameTime());
        }
    }

    /** Server-thread-only. Advances the plan by at most one decision. */
    public void tick() {
        assertServerThread();
        if (plan == null) {
            return;
        }
        if (!companion.isAlive() || companion.isRemoved()) {
            finish(PlanStatus.FAILED, Reasons.COMPANION_DIED);
            return;
        }
        if (awaitingGoalId == null) {
            if (waitingForSupervisorHandoff && companion.getSupervisor().isBusy()) {
                // The goal this plan superseded is still winding down inside the supervisor;
                // submitting now would be refused and wrongly end the replacement. It clears
                // within a tick or two, because the goal was already cancelled.
                return;
            }
            waitingForSupervisorHandoff = false;
            startStep();
            return;
        }
        TaskSupervisor supervisor = companion.getSupervisor();
        String finishedId = supervisor.lastOutcome().map(TaskOutcome::goalId).orElse(null);
        if (!awaitingGoalId.equals(finishedId)) {
            // Our step has not produced an outcome. If the supervisor is idle anyway, something
            // else took the body away from us and the plan has lost its footing.
            if (!supervisor.isBusy()) {
                finish(PlanStatus.CANCELLED, Reasons.SUPERSEDED);
            }
            return;
        }
        consume(supervisor.lastOutcome().orElseThrow());
    }

    private void startStep() {
        PlanStep step = plan.steps().get(stepIndex);
        ToolValidation.Accepted validated = validatedSteps.get(stepIndex);
        Goal goal;
        try {
            // Bound per step, never once per plan: targets despawn and players log out mid-plan.
            goal = validated.tool().builder().build(validated.arguments(), ToolContext.of(companion));
        } catch (ToolBindException error) {
            recordStep(step, "", OutcomeStatus.BLOCKED, error.reason(), error.getMessage());
            finish(PlanStatus.BLOCKED, error.reason());
            return;
        } catch (IllegalArgumentException | IllegalStateException error) {
            String detail = error.getMessage() == null ? "" : error.getMessage();
            recordStep(step, "", OutcomeStatus.BLOCKED, Reasons.INVALID_ARGUMENT, detail);
            finish(PlanStatus.BLOCKED, Reasons.INVALID_ARGUMENT);
            return;
        }
        // submit, never replace: a plan must not silently steamroll an order the owner just gave.
        if (companion.getSupervisor().submit(goal) != TaskSupervisor.SubmitResult.ACCEPTED) {
            finish(PlanStatus.CANCELLED, Reasons.SUPERSEDED);
            return;
        }
        awaitingGoalId = goal.id();
        activeGoal = goal;
    }

    private void consume(TaskOutcome outcome) {
        harvestEvidence(outcome);
        PlanStep step = plan.steps().get(stepIndex);
        recordStep(step, outcome.goalId(), outcome.status(), outcome.reason(), outcome.detail());
        awaitingGoalId = null;
        if (outcome.status() != OutcomeStatus.SUCCEEDED) {
            finish(statusOf(outcome.status()), outcome.reason());
            return;
        }
        stepIndex++;
        if (stepIndex >= plan.steps().size()) {
            finish(PlanStatus.SUCCEEDED, Reasons.GOAL_MET);
        }
        // Otherwise the next tick starts the next step, mirroring the supervisor's own settle.
    }

    private static PlanStatus statusOf(OutcomeStatus status) {
        return switch (status) {
            case SUCCEEDED -> PlanStatus.SUCCEEDED;
            case BLOCKED -> PlanStatus.BLOCKED;
            case CANCELLED -> PlanStatus.CANCELLED;
            case TIMED_OUT -> PlanStatus.TIMED_OUT;
            default -> PlanStatus.FAILED;
        };
    }

    private void recordStep(PlanStep step, String goalId, OutcomeStatus status,
            String reason, String detail) {
        stepOutcomes.add(new PlanStepOutcome(stepIndex, step.call(), goalId, status, reason, detail));
    }

    /**
     * Reads owner-inventory delivery evidence latched by the finished step's probe, updates the
     * standing order's ledger, and journals a receipt. Runs at most once per step: the null-and-guard
     * on {@code activeGoal} makes the {@code finish} inside {@code consume} a no-op, while an
     * abnormal end (cancel, supersede, shutdown, death) harvests with no outcome at all — the
     * probe's latched arrays outlive the goal because the {@link Goal} record holds the probe.
     */
    private void harvestEvidence(TaskOutcome outcome) {
        if (activeGoal == null) {
            return;
        }
        Goal finished = activeGoal;
        activeGoal = null;
        StandingOrder order = companion.getStandingOrder();
        OrderLedger ledger = order == null ? null : order.ledger();
        if (finished.probe() instanceof DeliveryEvidenceSource source) {
            boolean shortfallBlocked = outcome != null
                    && (outcome.status() == OutcomeStatus.BLOCKED
                            || outcome.status() == OutcomeStatus.TIMED_OUT);
            for (DeliveryEvidence evidence : source.deliveryEvidence()) {
                if (evidence.delivered() > 0) {
                    if (ledger != null) {
                        ledger.recordDelivered(evidence.item(), evidence.delivered());
                    }
                    if (companion.getContext() != null) {
                        companion.getContext().record(EventKind.DELIVERY_RECEIPT,
                                "delivered %d %s to owner".formatted(evidence.delivered(),
                                        evidence.item()),
                                evidence.item());
                    }
                    recordActivity("delivered %d %s to owner".formatted(evidence.delivered(),
                            evidence.item()));
                }
                if (shortfallBlocked && evidence.delivered() < evidence.requested()
                        && ledger != null) {
                    ledger.recordBlocked(evidence.item(), outcome.reason());
                }
            }
        } else if (ledger != null && outcome != null
                && outcome.status() == OutcomeStatus.SUCCEEDED) {
            PlanStep step = plan.steps().get(stepIndex);
            if ("craft".equals(outcome.kind())) {
                ledger.recordCrafted(argString(step, "item"));
            } else if ("mine".equals(outcome.kind())) {
                ledger.recordAcquired(argString(step, "resource"));
            }
        }
    }

    private static String argString(PlanStep step, String name) {
        JsonElement value = step.call().arguments().get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /**
     * Server-thread-only. Cancels the running step as well as the plan. Since stage 4b this
     * touches only the plan: disowning an in-flight planner request is the
     * {@link ConversationCoordinator}'s job, and callers that mean "stop everything" call its
     * {@code invalidateAll()} themselves.
     */
    public void cancel(String reason) {
        assertServerThread();
        if (plan == null) {
            return;
        }
        if (awaitingGoalId != null && companion.getSupervisor().isBusy()) {
            companion.getSupervisor().cancel(reason);
        }
        finish(PlanStatus.CANCELLED, reason);
    }

    /** Server-thread-only. Ends the plan without touching the supervisor, which is stopping too. */
    public void shutdown(String reason) {
        assertServerThread();
        if (plan != null) {
            finish(PlanStatus.FAILED, reason);
        }
    }

    private void finish(PlanStatus status, String reason) {
        harvestEvidence(null);
        waitingForSupervisorHandoff = false;
        Plan finished = plan;
        long elapsed = startMs == 0L ? 0L : Math.max(0L, System.currentTimeMillis() - startMs);
        List<PlanStepOutcome> outcomes = List.copyOf(stepOutcomes);

        plan = null;
        validatedSteps = List.of();
        stepOutcomes.clear();
        stepIndex = 0;
        awaitingGoalId = null;
        startMs = 0L;

        if (finished != null) {
            publish(finished, status, reason, outcomes, elapsed);
        }
    }

    private void publish(Plan finished, PlanStatus status, String reason,
            List<PlanStepOutcome> outcomes, long elapsedMs) {
        int completed = (int) outcomes.stream().filter(PlanStepOutcome::succeeded).count();
        lastOutcome = new PlanOutcome(finished.id(), status, reason, completed,
                finished.steps().size(), outcomes, elapsedMs);
        if (companion.getContext() != null) {
            companion.getContext().record(EventKind.PLAN_FINISHED,
                    "plan %s %s (%s)".formatted(finished.id(), status, reason),
                    lastOutcome.toCompactString());
            companion.getContext().triggers().arm(ReplanReason.PLAN_FINISHED,
                    status + " " + reason);
        }
        if (status != PlanStatus.REJECTED) {
            // A rejected plan never ran; its failure is dispatch's conversational report, not an
            // activity this companion performed.
            recordActivity("plan ended %s (%s)".formatted(
                    status.name().toLowerCase(Locale.ROOT), reason));
        }
        MineMate.LOGGER.info("Plan finished: {}", lastOutcome.toCompactString());
    }

    public boolean isActive() {
        return plan != null;
    }

    /** Whether the active plan owns the supervisor goal with this id. */
    public boolean ownsGoal(String goalId) {
        return plan != null && awaitingGoalId != null && awaitingGoalId.equals(goalId);
    }

    public Optional<Plan> currentPlan() {
        return Optional.ofNullable(plan);
    }

    /** Zero-based index of the step being run; meaningless when no plan is active. */
    public int currentStepIndex() {
        return stepIndex;
    }

    public Optional<PlanOutcome> lastOutcome() {
        return Optional.ofNullable(lastOutcome);
    }

    private void assertServerThread() {
        assert companion.getServer() != null && companion.getServer().isSameThread();
    }
}
