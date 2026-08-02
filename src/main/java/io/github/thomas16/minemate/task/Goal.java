package io.github.thomas16.minemate.task;

import com.player2.playerengine.tasks.base.Task;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** A re-submittable task request and its independent verifier. */
public record Goal(String id, String kind, String description, Supplier<Task> taskFactory,
        GoalProbe probe, int timeoutTicks, int stallTicks) {

    private static final AtomicLong IDS = new AtomicLong();

    public Goal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(taskFactory, "taskFactory");
        Objects.requireNonNull(probe, "probe");
    }

    public static String nextId() {
        return "minemate-" + IDS.incrementAndGet();
    }
}
