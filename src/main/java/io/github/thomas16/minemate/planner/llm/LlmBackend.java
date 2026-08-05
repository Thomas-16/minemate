package io.github.thomas16.minemate.planner.llm;

import java.util.concurrent.CompletableFuture;

/**
 * One provider's request and response mapping.
 *
 * <p>This is the pluggable seam: an OpenAI backend and an Anthropic backend differ only in how they
 * spell a tool definition and where they put the reply, and nothing above this interface should
 * have to know which is installed.
 */
public interface LlmBackend {

    /** Short identifier for logs and {@code /companion planner}, e.g. {@code openai:gpt-5.6-terra}. */
    String name();

    /** Must return immediately; all work belongs to the transport's executor. */
    CompletableFuture<LlmReply> complete(LlmPrompt prompt);

    default void close() {
    }
}
