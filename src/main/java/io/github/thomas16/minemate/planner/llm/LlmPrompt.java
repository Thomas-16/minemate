package io.github.thomas16.minemate.planner.llm;

import java.util.Objects;

/**
 * A provider-neutral prompt. Each backend decides how these four pieces map onto its own wire
 * format, which is the whole point of the split: swapping providers must not touch the prompt.
 *
 * @param system         the static role and rules; identical on every request, so it caches
 * @param user           the volatile part: rendered world context plus the order
 * @param toolSchemaJson {@code ToolRegistry.schemaJson()} verbatim, translated by the backend
 * @param cacheKey       a stable key for the provider's prompt cache, derived from the prefix
 */
public record LlmPrompt(String system, String user, String toolSchemaJson, String cacheKey) {

    public LlmPrompt {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(toolSchemaJson, "toolSchemaJson");
        cacheKey = cacheKey == null ? "" : cacheKey;
    }
}
