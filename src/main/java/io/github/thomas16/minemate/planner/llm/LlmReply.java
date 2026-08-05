package io.github.thomas16.minemate.planner.llm;

import io.github.thomas16.minemate.planner.PlanDisposition;
import io.github.thomas16.minemate.tool.ToolCall;

import java.util.List;

/**
 * What a model said and what it wants run.
 *
 * <p>The disposition makes an empty call list unambiguous: action replies contain world calls,
 * while terminal replies contain a status and message.
 */
public record LlmReply(String dialogue, List<ToolCall> calls, PlanDisposition disposition) {

    public LlmReply {
        dialogue = dialogue == null ? "" : dialogue.strip();
        calls = calls == null ? List.of() : List.copyOf(calls);
        disposition = disposition == null ? PlanDisposition.ACTION : disposition;
    }

    public boolean terminal() {
        return disposition.terminal();
    }
}
