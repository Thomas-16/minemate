package io.github.thomas16.minemate.planner;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded sequence of tool calls to run one at a time.
 *
 * @param dialogue what the companion says about this plan, or the message for a terminal disposition
 * @param disposition whether this contains executable actions or closes the standing order
 * @param contract the order contract the reply declared, or null for planners without one
 */
public record Plan(String id, String requestText, List<PlanStep> steps, String dialogue,
        PlanDisposition disposition, OrderContract contract) {

    /** Hard protocol bound; larger orders continue as coherent chunks through replanning. */
    public static final int MAX_STEPS = 8;

    private static final AtomicLong IDS = new AtomicLong();

    public Plan {
        Objects.requireNonNull(id, "id");
        requestText = requestText == null ? "" : requestText;
        dialogue = dialogue == null ? "" : dialogue;
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        disposition = disposition == null ? PlanDisposition.ACTION : disposition;
    }

    public Plan(String id, String requestText, List<PlanStep> steps, String dialogue,
            PlanDisposition disposition) {
        this(id, requestText, steps, dialogue, disposition, null);
    }

    /** Scripted and ordinary plans are executable unless they explicitly say otherwise. */
    public Plan(String id, String requestText, List<PlanStep> steps, String dialogue) {
        this(id, requestText, steps, dialogue, PlanDisposition.ACTION, null);
    }

    public static String nextId() {
        return "plan-" + IDS.incrementAndGet();
    }

    public static Plan of(String requestText, List<PlanStep> steps) {
        return new Plan(nextId(), requestText, steps, "", PlanDisposition.ACTION);
    }
}
