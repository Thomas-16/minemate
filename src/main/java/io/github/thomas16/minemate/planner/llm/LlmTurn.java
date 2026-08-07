package io.github.thomas16.minemate.planner.llm;

import java.util.Objects;

/**
 * One rendered conversational turn, provider-neutral. Backends map the role onto their own wire
 * vocabulary; the text is already final.
 */
public record LlmTurn(Role role, String text) {

    public enum Role {
        USER,
        ASSISTANT
    }

    public LlmTurn {
        Objects.requireNonNull(role, "role");
        text = text == null ? "" : text;
    }
}
