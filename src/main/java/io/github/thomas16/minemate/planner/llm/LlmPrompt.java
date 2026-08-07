package io.github.thomas16.minemate.planner.llm;

import java.util.List;
import java.util.Objects;

/**
 * A provider-neutral prompt. Each backend decides how these pieces map onto its own wire
 * format, which is the whole point of the split: swapping providers must not touch the prompt.
 *
 * @param system         the static role and rules, shared by every companion
 * @param systemIdentity the stable per-companion identity, still system-level but separately
 *                       cacheable by providers that support multiple prefix breakpoints
 * @param turns          the rendered conversation, oldest first; always starts and ends with a
 *                       user turn, with no two consecutive turns sharing a role
 * @param toolSchemaJson {@code ToolRegistry.schemaJson()} verbatim, translated by the backend
 * @param cacheKey       a stable key for the provider's prompt cache, derived from the prefix
 */
public record LlmPrompt(String system, String systemIdentity, List<LlmTurn> turns,
        String toolSchemaJson, String cacheKey) {

    public LlmPrompt {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(systemIdentity, "systemIdentity");
        turns = List.copyOf(Objects.requireNonNull(turns, "turns"));
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("prompt had no turns");
        }
        if (turns.get(0).role() != LlmTurn.Role.USER
                || turns.get(turns.size() - 1).role() != LlmTurn.Role.USER) {
            throw new IllegalArgumentException("rendered history must start and end with a user turn");
        }
        for (int index = 1; index < turns.size(); index++) {
            if (turns.get(index).role() == turns.get(index - 1).role()) {
                throw new IllegalArgumentException("consecutive turns shared a role");
            }
        }
        Objects.requireNonNull(toolSchemaJson, "toolSchemaJson");
        cacheKey = cacheKey == null ? "" : cacheKey;
    }

    /** OpenAI takes one instructions string, while Anthropic can keep the two cache boundaries. */
    public String combinedSystem() {
        return system + "\n\n" + systemIdentity;
    }
}
