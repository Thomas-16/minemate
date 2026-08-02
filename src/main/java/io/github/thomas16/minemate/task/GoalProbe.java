package io.github.thomas16.minemate.task;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.tasks.base.Task;

import io.github.thomas16.minemate.entity.CompanionEntity;

/** MineMate-owned verification for a PlayerEngine task. */
public interface GoalProbe {

    record Context(CompanionEntity companion, PlayerEngineController controller, Task task, int ticks) {
    }

    default void onStart(Context ctx) {
    }

    GoalProgress sample(Context ctx);

    /** Returns a machine reason when the goal cannot currently continue. */
    default String blockedReason(Context ctx) {
        return null;
    }
}
