package io.github.thomas16.minemate.task.sink;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.task.Goal;
import io.github.thomas16.minemate.task.TaskListener;
import io.github.thomas16.minemate.task.TaskOutcome;

/** Writes compact task outcomes to the server log. */
public final class LoggingTaskListener implements TaskListener {
    @Override
    public void onGoalFinished(CompanionEntity companion, Goal goal, TaskOutcome outcome) {
        MineMate.LOGGER.info("[task] companion={} {}", companion.getUUID(), outcome.toCompactString());
    }
}
