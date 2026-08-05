package io.github.thomas16.minemate.planner.llm;

import io.github.thomas16.minemate.planner.OrderContract;
import io.github.thomas16.minemate.planner.PlanDisposition;
import io.github.thomas16.minemate.tool.ToolCall;

import java.util.List;

/**
 * What a model said and what it wants run.
 *
 * <p>The disposition makes an empty call list unambiguous: action replies contain world calls,
 * while terminal replies contain a status and message.
 *
 * @param contract the order contract declared alongside an action reply, or null when the reply
 *                 declared none; terminal replies never carry one
 */
public record LlmReply(String dialogue, List<ToolCall> calls, PlanDisposition disposition,
        OrderContract contract) {

    public LlmReply {
        dialogue = dialogue == null ? "" : dialogue.strip();
        calls = calls == null ? List.of() : List.copyOf(calls);
        disposition = disposition == null ? PlanDisposition.ACTION : disposition;
    }

    public LlmReply(String dialogue, List<ToolCall> calls, PlanDisposition disposition) {
        this(dialogue, calls, disposition, null);
    }

    public boolean terminal() {
        return disposition.terminal();
    }
}
