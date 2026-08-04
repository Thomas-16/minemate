package io.github.thomas16.minemate.task.sink;

import io.github.thomas16.minemate.context.CompanionContext;
import io.github.thomas16.minemate.context.EventKind;
import io.github.thomas16.minemate.context.ReplanReason;
import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.task.Goal;
import io.github.thomas16.minemate.task.TaskListener;
import io.github.thomas16.minemate.task.TaskOutcome;

/**
 * Feeds goal lifecycle into each companion's journal, and arms a replan trigger on every outcome.
 *
 * <p>One stateless global instance serves every companion. {@code TaskListeners} fans out
 * unfiltered, but each callback is handed the companion it concerns, so writing to that companion's
 * journal is correct by construction — and, unlike a per-companion listener, it cannot leak, since
 * {@code TaskListeners} has no unregister.
 *
 * <p>Goal progress is deliberately not journalled: at one event per 40 ticks it would evict
 * everything else worth remembering.
 */
public final class JournalTaskListener implements TaskListener {

    @Override
    public void onGoalStarted(CompanionEntity companion, Goal goal) {
        CompanionContext context = companion.getContext();
        if (context != null) {
            context.record(EventKind.GOAL_STARTED, "started " + goal.description(), goal.id());
        }
    }

    @Override
    public void onGoalFinished(CompanionEntity companion, Goal goal, TaskOutcome outcome) {
        CompanionContext context = companion.getContext();
        if (context == null) {
            return;
        }
        context.record(EventKind.GOAL_FINISHED,
                "%s %s (%s)".formatted(goal.description(), outcome.status(), outcome.reason()),
                outcome.toCompactString());
        context.triggers().arm(ReplanReason.TASK_OUTCOME,
                goal.kind() + " " + outcome.status() + " " + outcome.reason());
    }
}
