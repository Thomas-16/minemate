package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.context.EventKind;
import io.github.thomas16.minemate.context.ReplanReason;
import io.github.thomas16.minemate.entity.CompanionEntity;
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

    private final CompanionEntity companion;

    private Plan plan;
    private List<ToolValidation.Accepted> validatedSteps = List.of();
    private final List<PlanStepOutcome> stepOutcomes = new ArrayList<>();
    private int stepIndex;
    private String awaitingGoalId;
    private long startMs;
    private PlanOutcome lastOutcome;
    private int plannerRequestGeneration;
    private boolean plannerRequestPending;

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
        if (plan != null) {
            return StartResult.BUSY;
        }
        if (newPlan.steps().isEmpty()) {
            publish(newPlan, PlanStatus.REJECTED, Reasons.PLAN_EMPTY, List.of(), 0L);
            return StartResult.REJECTED;
        }
        if (newPlan.steps().size() > Plan.MAX_STEPS) {
            publish(newPlan, PlanStatus.REJECTED, Reasons.PLAN_TOO_LONG, List.of(), 0L);
            return StartResult.REJECTED;
        }
        List<ToolValidation.Accepted> accepted = new ArrayList<>(newPlan.steps().size());
        for (int index = 0; index < newPlan.steps().size(); index++) {
            PlanStep step = newPlan.steps().get(index);
            ToolValidation validation = ToolRegistry.defaults().validate(step.call());
            if (validation instanceof ToolValidation.Rejected rejected) {
                publish(newPlan, PlanStatus.REJECTED, rejected.reason(), List.of(), 0L);
                return StartResult.REJECTED;
            }
            ToolValidation.Accepted ok = (ToolValidation.Accepted) validation;
            // A continuous tool never finishes on its own, so anything after it would never run.
            if (ok.tool().continuous() && index != newPlan.steps().size() - 1) {
                publish(newPlan, PlanStatus.REJECTED, Reasons.INVALID_ARGUMENT, List.of(), 0L);
                return StartResult.REJECTED;
            }
            accepted.add(ok);
        }

        plan = newPlan;
        validatedSteps = List.copyOf(accepted);
        stepOutcomes.clear();
        stepIndex = 0;
        awaitingGoalId = null;
        startMs = System.currentTimeMillis();
        if (companion.getContext() != null) {
            companion.getContext().record(EventKind.PLAN_STARTED,
                    "plan %s started (%d steps)".formatted(plan.id(), plan.steps().size()),
                    plan.requestText());
        }
        MineMate.LOGGER.info("Plan {} started with {} steps", plan.id(), plan.steps().size());
        return StartResult.ACCEPTED;
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
    }

    private void consume(TaskOutcome outcome) {
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
     * Server-thread-only. Claims the next planner request slot and returns its generation, which
     * {@link #isCurrentPlannerRequest} later checks.
     *
     * <p>A planner answers on its own schedule, and a slow one leaves a window in which the owner
     * can cancel, stop, or ask for something else entirely. Without this, that older answer still
     * arrives and starts a plan nobody is waiting for any more.
     */
    public int beginPlannerRequest() {
        assertServerThread();
        plannerRequestPending = true;
        return ++plannerRequestGeneration;
    }

    /** Whether a completed planner request is still the one the owner is waiting for. */
    public boolean isCurrentPlannerRequest(int generation) {
        return generation == plannerRequestGeneration;
    }

    /** Server-thread-only. Marks the in-flight request answered, whatever the answer was. */
    public void completePlannerRequest(int generation) {
        assertServerThread();
        if (generation == plannerRequestGeneration) {
            plannerRequestPending = false;
        }
    }

    /** Whether a planner has been asked for a plan that has not come back yet. */
    public boolean isPlannerRequestPending() {
        return plannerRequestPending;
    }

    /**
     * Server-thread-only. Abandons any in-flight planner request without touching a running plan.
     * {@code /companion stop} needs this even with no plan active, because the request that would
     * have produced one may still be outstanding.
     */
    public void discardPendingPlannerRequests() {
        assertServerThread();
        plannerRequestGeneration++;
        plannerRequestPending = false;
    }

    /** Server-thread-only. Cancels the running step as well as the plan. */
    public void cancel(String reason) {
        assertServerThread();
        // Before the early return: a cancel with no plan yet still has to disown a pending request.
        discardPendingPlannerRequests();
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
        discardPendingPlannerRequests();
        if (plan != null) {
            finish(PlanStatus.FAILED, reason);
        }
    }

    private void finish(PlanStatus status, String reason) {
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
        MineMate.LOGGER.info("Plan finished: {}", lastOutcome.toCompactString());
    }

    public boolean isActive() {
        return plan != null;
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
