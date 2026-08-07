package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.tool.ToolCall;

import java.util.List;

/**
 * What the companion's brain answered: speech, an action commitment, an order close, or a
 * contract awaiting its covering calls. Conversation is the baseline shape — speech with no
 * actions and no close creates no plan, order, or contract anywhere.
 *
 * <p>A {@link Plan} is constructed only after the actions here have passed validation; this type
 * deliberately holds raw {@link ToolCall}s so nothing executable exists before that gate.
 *
 * @param speech what the companion says, possibly blank beside actions because a live provider
 *               may return tool calls without a text block
 * @param close  a request to close the standing order; never mixes with actions or a contract
 */
public record CompanionResponse(String speech, List<ToolCall> actions, OrderContract contract,
        OrderClose close) {

    public CompanionResponse {
        speech = speech == null ? "" : speech.strip();
        actions = actions == null ? List.of() : List.copyOf(actions);
        if (close != null && (!actions.isEmpty() || contract != null)) {
            throw new IllegalArgumentException("a close never mixes with actions or a contract");
        }
    }

    public static CompanionResponse speech(String speech) {
        return new CompanionResponse(speech, List.of(), null, null);
    }

    public static CompanionResponse actions(String speech, List<ToolCall> actions,
            OrderContract contract) {
        return new CompanionResponse(speech, actions, contract, null);
    }

    public static CompanionResponse close(String speech, OrderClose close) {
        return new CompanionResponse(speech, List.of(), null, close);
    }

    /** Speech with no actions and no close: pure conversation. */
    public boolean isConversation() {
        return close == null && actions.isEmpty() && contract == null;
    }

    /** The single-call provider habit: a declared contract still waiting for its world calls. */
    public boolean isContractOnly() {
        return close == null && actions.isEmpty() && contract != null;
    }
}
