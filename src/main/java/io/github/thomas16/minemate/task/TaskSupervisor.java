package io.github.thomas16.minemate.task;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.executor.RollbackPolicy;
import com.player2.playerengine.executor.StepExecution;
import com.player2.playerengine.executor.StepState;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.base.TaskChain;
import com.player2.playerengine.tasks.crafting.DescribesProgress;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.integration.CompanionMovement;

import java.util.Objects;
import java.util.Optional;

/**
 * Server-thread coordinator that owns goal verification and terminal outcomes.
 * Active goals intentionally are not persisted across entity reloads in stage 2.
 */
public final class TaskSupervisor {
    public enum SubmitResult {
        ACCEPTED,
        BUSY,
        REJECTED
    }

    private static final int SAMPLE_INTERVAL_TICKS = 5;
    private static final int PROGRESS_INTERVAL_TICKS = 40;
    private static final double ADVANCE_EPSILON = 1.0e-3;

    /** Consecutive ticks another chain may own the body before the goal is given up on. */
    private static final int PREEMPT_LIMIT_TICKS = 600;

    private final CompanionEntity companion;
    private final PlayerEngineController controller;

    private Goal goal;
    private StepExecution execution;
    private String activeStepId;
    private Task runningTask;
    private Goal pending;
    private int ticks;
    private long startMs;
    private double bestAdvance;
    private int ticksSinceAdvance;
    private GoalProgress lastProgress;
    private TaskOutcome lastOutcome;
    private TaskActivitySnapshot currentActivity;
    private boolean engineFinishHint;
    private String preemptingChain;
    private int preemptedTicks;

    public TaskSupervisor(CompanionEntity companion) {
        this.companion = Objects.requireNonNull(companion, "companion");
        this.controller = Objects.requireNonNull(companion.getController(), "controller");
    }

    /** Server-thread-only. Queues a goal unless another goal is active or pending. */
    public SubmitResult submit(Goal newGoal) {
        assertServerThread();
        if (newGoal == null) {
            return SubmitResult.REJECTED;
        }
        if (isBusy()) {
            return SubmitResult.BUSY;
        }
        pending = newGoal;
        return SubmitResult.ACCEPTED;
    }

    /** Server-thread-only. Cancels current work and queues the replacement for the next tick. */
    public SubmitResult replace(Goal newGoal) {
        assertServerThread();
        if (newGoal == null) {
            return SubmitResult.REJECTED;
        }
        if (goal != null) {
            pending = newGoal;
            finish(OutcomeStatus.CANCELLED, Reasons.SUPERSEDED);
        } else if (pending != null) {
            Goal superseded = pending;
            pending = newGoal;
            finishQueued(superseded, OutcomeStatus.CANCELLED, Reasons.SUPERSEDED);
        } else {
            pending = newGoal;
        }
        return SubmitResult.ACCEPTED;
    }

    /** Server-thread-only. Cancels the active or queued goal with the supplied machine reason. */
    public void cancel(String reason) {
        assertServerThread();
        if (goal != null) {
            finish(OutcomeStatus.CANCELLED, reason);
        } else if (pending != null) {
            finishPending(OutcomeStatus.CANCELLED, reason);
        }
    }

    /** Server-thread-only. Samples and advances the active goal once per entity tick. */
    public void tick() {
        assertServerThread();
        try {
            if (!companion.isAlive() || companion.isRemoved()) {
                if (goal != null) {
                    finish(OutcomeStatus.FAILED, Reasons.COMPANION_DIED);
                } else if (pending != null) {
                    finishPending(OutcomeStatus.FAILED, Reasons.COMPANION_DIED);
                }
                return;
            }
            if (pending != null) {
                startPending();
            }
            if (goal == null) {
                return;
            }

            ticks++;
            updateChainOwnership();
            if (preemptedTicks >= PREEMPT_LIMIT_TICKS) {
                finish(OutcomeStatus.BLOCKED, Reasons.PREEMPTED);
                return;
            }
            if (ticks % SAMPLE_INTERVAL_TICKS == 0 && !sampleProgress()) {
                return;
            }
            if (lastProgress != null && lastProgress.complete()) {
                finish(OutcomeStatus.SUCCEEDED, Reasons.GOAL_MET);
                return;
            }

            GoalProbe.Context context = context();
            TaskTerminalResult terminal;
            try {
                terminal = goal.probe().terminalResult(context);
            } catch (Throwable error) {
                MineMate.LOGGER.error("Goal probe failed while mapping terminal result for {}", goal.id(), error);
                finish(OutcomeStatus.FAILED, Reasons.ENGINE_FAILED);
                return;
            }
            if (terminal != null) {
                // Structured PlayerEngine outcomes can arrive between the five-tick samples.
                // Refresh MineMate's independent verifier so partial work is never reported as 0/N.
                if (!sampleProgress() || goal == null) {
                    return;
                }
                if (lastProgress != null && lastProgress.complete()) {
                    finish(OutcomeStatus.SUCCEEDED, Reasons.GOAL_MET);
                    return;
                }
                finish(terminal.status(), terminal.reason(), terminal.moved());
                return;
            }
            String blocked;
            try {
                blocked = goal.probe().blockedReason(context);
            } catch (Throwable error) {
                MineMate.LOGGER.error("Goal probe failed while checking {}", goal.id(), error);
                finish(OutcomeStatus.FAILED, Reasons.ENGINE_FAILED);
                return;
            }
            if (blocked != null) {
                finish(OutcomeStatus.BLOCKED, blocked);
                return;
            }
            if ((execution != null && execution.isTerminal()) || engineFinishHint) {
                finishFromEngine();
                return;
            }
            if (goal.timeoutTicks() > 0 && ticks >= goal.timeoutTicks()) {
                finish(OutcomeStatus.TIMED_OUT, Reasons.TIMEOUT);
                return;
            }
            if (goal.stallTicks() > 0 && ticksSinceAdvance >= goal.stallTicks()) {
                finish(OutcomeStatus.TIMED_OUT, Reasons.NO_PROGRESS);
                return;
            }
            if (ticks % PROGRESS_INTERVAL_TICKS == 0 && lastProgress != null) {
                TaskListeners.fireProgress(companion, goal, annotate(lastProgress));
            }
        } finally {
            refreshActivity();
        }
    }

    /**
     * MineMate's goals run on PlayerEngine's user chain at a flat priority 50, and
     * survival chains outrank it (mob defense reaches 80). While one of them owns
     * the body the goal's task is interrupted, yet its step stays RUNNING — so
     * without this the supervisor keeps narrating progress for a task that is not
     * running, and eventually blames the stall on the goal itself.
     */
    private void updateChainOwnership() {
        TaskChain current = controller.getTaskRunner().getCurrentTaskChain();
        if (current == null || current == controller.getUserTaskChain()) {
            if (preemptingChain != null) {
                // Our task resumes from scratch next tick; start it from a clean slate.
                CompanionMovement.halt(controller);
            }
            preemptingChain = null;
            preemptedTicks = 0;
            return;
        }
        if (preemptingChain == null && !controller.getBaritone().getPathingBehavior().isPathing()) {
            // The handover clears neither pathing nor forced inputs. Drop whatever the
            // interrupted task was holding, unless the new owner is already steering.
            CompanionMovement.halt(controller);
        }
        preemptingChain = current.getName();
        preemptedTicks++;
    }

    private GoalProgress annotate(GoalProgress progress) {
        if (preemptingChain == null) {
            return progress;
        }
        return new GoalProgress(progress.have(), progress.want(), progress.advance(),
                progress.text() + " - paused: " + preemptingChain);
    }

    /** Server-thread-only. */
    public boolean isBusy() {
        assertServerThread();
        return goal != null || pending != null;
    }

    /** Server-thread-only. Includes a goal queued to start on the next tick. */
    public Optional<Goal> currentGoal() {
        assertServerThread();
        return Optional.ofNullable(goal != null ? goal : pending);
    }

    /** Server-thread-only. */
    public Optional<GoalProgress> currentProgress() {
        assertServerThread();
        return Optional.ofNullable(lastProgress);
    }

    /** Server-thread-only. Names the PlayerEngine chain currently holding the body, if not ours. */
    public Optional<String> preemptingChain() {
        assertServerThread();
        return Optional.ofNullable(preemptingChain);
    }

    /** Server-thread-only. */
    public Optional<TaskOutcome> lastOutcome() {
        assertServerThread();
        return Optional.ofNullable(lastOutcome);
    }

    /** Server-thread-only canonical activity telemetry for snapshots, status, and tests. */
    public Optional<TaskActivitySnapshot> currentActivity() {
        assertServerThread();
        return Optional.ofNullable(currentActivity);
    }

    /** Server-thread-only. Stops tracked work before the entity/controller is removed. */
    public void shutdown(String reason) {
        assertServerThread();
        if (goal != null) {
            finish(OutcomeStatus.FAILED, reason);
        } else if (pending != null) {
            finishPending(OutcomeStatus.FAILED, reason);
        }
    }

    private void startPending() {
        goal = pending;
        pending = null;
        ticks = 0;
        startMs = System.currentTimeMillis();
        bestAdvance = -Double.MAX_VALUE;
        ticksSinceAdvance = 0;
        lastProgress = null;
        engineFinishHint = false;
        preemptingChain = null;
        preemptedTicks = 0;
        activeStepId = goal.id();

        try {
            runningTask = Objects.requireNonNull(goal.taskFactory().get(), "task factory returned null");
            String stepId = activeStepId;
            execution = controller.runUserTaskTracked(stepId, goal.kind(), runningTask, RollbackPolicy.NONE,
                    () -> {
                        if (stepId.equals(activeStepId)) {
                            engineFinishHint = true;
                        }
                    });
            GoalProbe.Context context = context();
            goal.probe().onStart(context);
            if (!sampleProgress() || goal == null) {
                return;
            }
            TaskListeners.fireStarted(companion, goal);
            if (goal != null && execution.isTerminal()) {
                if (lastProgress != null && lastProgress.complete()) {
                    finish(OutcomeStatus.SUCCEEDED, Reasons.GOAL_MET);
                } else {
                    finishFromEngine();
                }
            }
        } catch (Throwable error) {
            if (error instanceof GoalStartException controlled) {
                MineMate.LOGGER.info("Goal {} could not start: {}", goal.id(), controlled.result().reason());
                sampleControlledStartFailure();
                finish(controlled.result().status(), controlled.result().reason(), controlled.result().moved());
                return;
            }
            String reason = Reasons.TARGET_GONE.equals(error.getMessage())
                    || Reasons.OWNER_OFFLINE.equals(error.getMessage())
                    ? error.getMessage() : Reasons.ENGINE_BLOCKED;
            MineMate.LOGGER.warn("Could not start goal {}: {}", goal.id(), error.toString());
            finish(OutcomeStatus.BLOCKED, reason);
        }
    }

    private boolean sampleProgress() {
        try {
            GoalProgress progress = Objects.requireNonNull(goal.probe().sample(context()),
                    "goal probe returned null");
            lastProgress = progress;
            if (progress.advance() > bestAdvance + ADVANCE_EPSILON) {
                bestAdvance = progress.advance();
                ticksSinceAdvance = 0;
            } else if (ticks > 0 && preemptingChain == null) {
                // A preempted task cannot advance; that is not the goal stalling.
                ticksSinceAdvance += SAMPLE_INTERVAL_TICKS;
            }
            return true;
        } catch (Throwable error) {
            MineMate.LOGGER.error("Goal probe failed while sampling {}", goal.id(), error);
            finish(OutcomeStatus.FAILED, Reasons.ENGINE_FAILED);
            return false;
        }
    }

    private GoalProbe.Context context() {
        return new GoalProbe.Context(companion, controller, runningTask, ticks);
    }

    private void sampleControlledStartFailure() {
        try {
            lastProgress = Objects.requireNonNull(goal.probe().sample(context()),
                    "goal probe returned null");
        } catch (Throwable ignored) {
            // A controlled start failure still owns its typed outcome. Some legacy probes
            // require a running task and therefore cannot add counts at this point.
        }
    }

    private void finishFromEngine() {
        if (execution == null) {
            finish(OutcomeStatus.FAILED, Reasons.ENGINE_FAILED);
            return;
        }
        // PlayerEngine can finish between our five-tick samples. The verifier
        // remains authoritative, so always refresh it before trusting a
        // terminal engine state in either direction.
        if (!sampleProgress() || goal == null) {
            return;
        }
        if (lastProgress != null && lastProgress.complete()) {
            finish(OutcomeStatus.SUCCEEDED, Reasons.GOAL_MET);
            return;
        }
        StepState state = execution.getState();
        String log = safeEngineLog();
        if (state == StepState.SUCCEEDED) {
            finish(OutcomeStatus.FAILED, Reasons.ENGINE_FALSE_SUCCESS);
        } else if (state == StepState.FAILED && log.contains("CANCELLED_OPERATOR")) {
            finish(OutcomeStatus.CANCELLED, Reasons.CANCELLED_BY_OWNER);
        } else if (state == StepState.FAILED && log.contains("CANCELLED_SUPERSEDED")) {
            finish(OutcomeStatus.CANCELLED, Reasons.SUPERSEDED);
        } else if (state == StepState.FAILED && log.contains("FOLLOWED_TARGET_GONE")) {
            finish(OutcomeStatus.CANCELLED, Reasons.TARGET_GONE);
        } else if (state == StepState.BLOCKED || state == StepState.ROLLED_BACK) {
            finish(OutcomeStatus.BLOCKED, Reasons.ENGINE_BLOCKED);
        } else {
            finish(OutcomeStatus.FAILED, Reasons.ENGINE_FAILED);
        }
    }

    private void finish(OutcomeStatus status, String reason) {
        finish(status, reason, -1);
    }

    private void finish(OutcomeStatus status, String reason, int moved) {
        Goal finishedGoal = goal;
        StepExecution finishedExecution = execution;
        String stepId = activeStepId;
        GoalProgress progress = lastProgress;
        long elapsed = startMs == 0L ? 0L : Math.max(0L, System.currentTimeMillis() - startMs);

        goal = null;
        execution = null;
        activeStepId = null;
        runningTask = null;
        engineFinishHint = false;
        lastProgress = null;
        preemptingChain = null;
        preemptedTicks = 0;
        currentActivity = null;

        if (finishedGoal == null) {
            return;
        }
        if (finishedExecution != null && finishedExecution.getState() == StepState.RUNNING && stepId != null) {
            // Cancelling routes through UserTaskChain.onTaskFinish, which stops the legs itself.
            controller.getStepExecutorAdapter().cancel(stepId);
        } else {
            // No cancel to route through: nothing else would drop a latched input.
            CompanionMovement.halt(controller);
        }
        int have = progress == null ? 0 : progress.have();
        int want = progress == null ? 0 : progress.want();
        String engineState = finishedExecution == null ? "NONE" : finishedExecution.getState().name();
        String engineLog = finishedExecution == null || finishedExecution.getLastLogEntry() == null
                ? "" : finishedExecution.getLastLogEntry();
        lastOutcome = new TaskOutcome(finishedGoal.id(), finishedGoal.kind(), status, reason,
                detail(finishedGoal, status, reason), moved, have, want,
                elapsed, engineState, engineLog);
        TaskListeners.fireFinished(companion, finishedGoal, lastOutcome);
    }

    private void finishPending(OutcomeStatus status, String reason) {
        Goal finishedGoal = pending;
        pending = null;
        finishQueued(finishedGoal, status, reason);
    }

    private void refreshActivity() {
        if (goal == null || runningTask == null) {
            currentActivity = null;
            return;
        }
        String phase = describedProgress(runningTask);
        if (phase.isEmpty() && lastProgress != null) {
            phase = lastProgress.text();
        }
        String tree;
        try {
            tree = bounded(runningTask.getTaskTree(), 1200);
        } catch (Throwable error) {
            tree = "";
        }
        currentActivity = new TaskActivitySnapshot(goal.id(), goal.kind(), goal.executionProfile(),
                ticks, goal.timeoutTicks(), phase, tree, lastProgress,
                preemptingChain == null ? "" : preemptingChain);
    }

    private static String describedProgress(Task root) {
        String[] result = new String[1];
        try {
            root.thisOrChildSatisfies(task -> {
                if (task instanceof DescribesProgress described) {
                    String text = described.describeProgress();
                    if (text != null && !text.isBlank()) {
                        result[0] = text;
                        return true;
                    }
                }
                return false;
            });
        } catch (Throwable ignored) {
            return "";
        }
        return result[0] == null ? "" : bounded(result[0], 300);
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private void finishQueued(Goal finishedGoal, OutcomeStatus status, String reason) {
        if (finishedGoal == null) {
            return;
        }
        lastOutcome = new TaskOutcome(finishedGoal.id(), finishedGoal.kind(), status, reason,
                detail(finishedGoal, status, reason), -1, 0, 0, 0L, "NONE", "");
        TaskListeners.fireFinished(companion, finishedGoal, lastOutcome);
    }

    private String safeEngineLog() {
        String log = execution.getLastLogEntry();
        return log == null ? "" : log;
    }

    private static String detail(Goal goal, OutcomeStatus status, String reason) {
        return switch (reason) {
            case Reasons.GOAL_MET -> "Completed: " + goal.description();
            case Reasons.NO_PROGRESS -> "No progress on: " + goal.description();
            case Reasons.TIMEOUT -> "Timed out: " + goal.description();
            case Reasons.CANCELLED_BY_OWNER -> "Stopped: " + goal.description();
            case Reasons.PREEMPTED -> "The companion's own survival behavior took over during: "
                    + goal.description();
            case Reasons.SUPERSEDED -> "Replaced by a new order: " + goal.description();
            case Reasons.COMPANION_DIED -> "Companion died while trying to " + goal.description();
            case Reasons.COMPANION_REMOVED -> "Companion was removed while trying to " + goal.description();
            case Reasons.UNKNOWN_RESOURCE -> "Unknown resource for: " + goal.description();
            case Reasons.RESOURCE_UNAVAILABLE -> "Resource unavailable for: " + goal.description();
            case Reasons.TARGET_GONE -> "Target is gone for: " + goal.description();
            case Reasons.OWNER_OFFLINE -> "Player is offline for: " + goal.description();
            case Reasons.NO_CRAFTING_RECIPE -> "No crafting recipe for: " + goal.description();
            case Reasons.MATERIALS_UNAVAILABLE -> "Required crafting materials are unavailable for: "
                    + goal.description();
            case Reasons.NO_SMELTING_RECIPE -> "No standard furnace recipe for: " + goal.description();
            case Reasons.NO_FUEL, Reasons.PARTIAL_OUT_OF_FUEL -> "Furnace fuel is insufficient for: "
                    + goal.description();
            case Reasons.FURNACE_TAMPERED, Reasons.FURNACE_GONE, Reasons.FURNACE_IN_USE,
                    Reasons.SMELT_STALLED, Reasons.SETUP_FAILED -> "Furnace work could not continue ("
                            + reason + "): " + goal.description();
            case Reasons.INSUFFICIENT_ITEMS, Reasons.INSUFFICIENT_SPACE,
                    Reasons.CONTAINER_MISSING, Reasons.CONTAINER_TOO_FAR,
                    Reasons.CONTAINER_UNREACHABLE, Reasons.CONTAINER_UNSUPPORTED,
                    Reasons.CONTAINER_CHANGED -> "Storage transfer could not finish (" + reason + "): "
                            + goal.description();
            case Reasons.ENGINE_FALSE_SUCCESS -> "Task ended before its goal was met: " + goal.description();
            case Reasons.ENGINE_BLOCKED -> "Task engine was blocked on: " + goal.description();
            default -> status + ": " + goal.description();
        };
    }

    private void assertServerThread() {
        assert companion.getServer() != null && companion.getServer().isSameThread();
    }
}
