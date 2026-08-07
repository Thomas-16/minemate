package io.github.thomas16.minemate.planner;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded sequence of tool calls to run one at a time.
 *
 * <p>Action-only: a plan exists because a {@link CompanionResponse}'s actions were accepted.
 * Speech and order closes never become plans, so there is no such thing as an empty or terminal
 * plan.
 */
public record Plan(String id, String requestText, List<PlanStep> steps) {

    /** Hard protocol bound; larger orders continue as coherent chunks through replanning. */
    public static final int MAX_STEPS = 8;

    private static final AtomicLong IDS = new AtomicLong();

    public Plan {
        Objects.requireNonNull(id, "id");
        requestText = requestText == null ? "" : requestText;
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
    }

    public static String nextId() {
        return "plan-" + IDS.incrementAndGet();
    }

    public static Plan of(String requestText, List<PlanStep> steps) {
        return new Plan(nextId(), requestText, steps);
    }
}
