package io.github.thomas16.minemate.planner.llm;

import io.github.thomas16.minemate.tool.ToolCall;

import java.util.List;

/**
 * What a model said and what it wants run.
 *
 * <p>An empty {@link #calls()} list is not a failure. It is how the model says the order is already
 * satisfied, and it is what stops a trivial order from spending its whole replan budget.
 */
public record LlmReply(String dialogue, List<ToolCall> calls) {

    public LlmReply {
        dialogue = dialogue == null ? "" : dialogue.strip();
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    public boolean talkOnly() {
        return calls.isEmpty();
    }
}
