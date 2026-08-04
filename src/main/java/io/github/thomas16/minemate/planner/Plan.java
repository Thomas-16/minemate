package io.github.thomas16.minemate.planner;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded sequence of tool calls to run one at a time.
 *
 * @param dialogue what the companion says about this plan; stage 4 only logs it, stage 8 speaks it
 */
public record Plan(String id, String requestText, List<PlanStep> steps, String dialogue) {

    /** Long plans are usually a planner hallucinating; replanning after each outcome beats a long chain. */
    public static final int MAX_STEPS = 8;

    private static final AtomicLong IDS = new AtomicLong();

    public Plan {
        Objects.requireNonNull(id, "id");
        requestText = requestText == null ? "" : requestText;
        dialogue = dialogue == null ? "" : dialogue;
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
    }

    public static String nextId() {
        return "plan-" + IDS.incrementAndGet();
    }

    public static Plan of(String requestText, List<PlanStep> steps) {
        return new Plan(nextId(), requestText, steps, "");
    }
}
