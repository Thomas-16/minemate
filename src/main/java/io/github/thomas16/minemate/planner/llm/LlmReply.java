package io.github.thomas16.minemate.planner.llm;

import io.github.thomas16.minemate.planner.OrderClose;
import io.github.thomas16.minemate.planner.OrderContract;
import io.github.thomas16.minemate.tool.ToolCall;

import java.util.List;

/**
 * What a model said and what it wants run.
 *
 * <p>An action reply carries world calls and possibly a contract; a close reply carries an
 * {@link OrderClose} and nothing executable.
 *
 * @param close    the order close the reply requested, or null for an action reply
 * @param contract the order contract declared alongside an action reply, or null when the reply
 *                 declared none; close replies never carry one
 */
public record LlmReply(String dialogue, List<ToolCall> calls, OrderClose close,
        OrderContract contract) {

    public LlmReply {
        dialogue = dialogue == null ? "" : dialogue.strip();
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    public LlmReply(String dialogue, List<ToolCall> calls, OrderClose close) {
        this(dialogue, calls, close, null);
    }

    public boolean terminal() {
        return close != null;
    }
}
