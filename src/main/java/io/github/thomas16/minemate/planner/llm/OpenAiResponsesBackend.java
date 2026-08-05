package io.github.thomas16.minemate.planner.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import io.github.thomas16.minemate.MineMate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI Responses API backend.
 *
 * <p>Responses rather than Chat Completions for two reasons: reasoning models are documented to use
 * tools better through it, and from GPT-5.4 on, tool calling is simply not supported in Chat
 * Completions when reasoning is off. Both of those matter here, since every request this sends is a
 * tool-calling request.
 *
 * <p>Two details in the wire format are easy to get wrong and expensive to debug. Tool definitions
 * are <em>flat</em> ({@code {type, name, parameters}}) and not wrapped in a {@code function} object;
 * that wrapper belongs to Chat Completions. And {@code output} is a heterogeneous array whose order
 * is not guaranteed, typically leading with a {@code reasoning} item, so it must be filtered by
 * {@code type} and never indexed.
 */
public final class OpenAiResponsesBackend implements LlmBackend {

    private static final String PATH = "/responses";
    /** One retry only. A planner request the owner is waiting on is not worth a long backoff chain. */
    private static final int MAX_ATTEMPTS = 2;
    private static final long MAX_RETRY_DELAY_MS = 5_000L;

    private final HttpTransport transport;
    private final String url;
    private final String model;
    private final String apiKey;
    private final String reasoningEffort;
    private final String verbosity;
    private final int maxOutputTokens;

    public OpenAiResponsesBackend(HttpTransport transport, String baseUrl, String model, String apiKey,
            String reasoningEffort, String verbosity, int maxOutputTokens) {
        this.transport = transport;
        this.url = trimTrailingSlash(baseUrl) + PATH;
        this.model = model;
        this.apiKey = apiKey;
        this.reasoningEffort = reasoningEffort;
        this.verbosity = verbosity;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public String name() {
        return "openai:" + model;
    }

    @Override
    public CompletableFuture<LlmReply> complete(LlmPrompt prompt) {
        String body = requestBody(prompt).toString();
        return send(body, 1);
    }

    @Override
    public void close() {
        transport.close();
    }

    private CompletableFuture<LlmReply> send(String body, int attempt) {
        // Debug only, and safe to enable: the body carries the prompt and the tools, never the key,
        // which lives in a header. This is the only practical way to see what a provider actually
        // rejected, because the error text rarely names the field.
        MineMate.LOGGER.debug("Planner request {}: {}", attempt, body);
        return transport.post(url, headers(), body).thenCompose(reply -> {
            MineMate.LOGGER.debug("Planner reply {}: {}", reply.status(), reply.body());
            if (reply.ok()) {
                // Throwing here completes the returned future exceptionally, which is exactly the
                // treatment a nonsense body deserves: the owner is told planning failed.
                return CompletableFuture.completedFuture(parseReply(reply.body()));
            }
            LlmException error = errorOf(reply);
            if (attempt < MAX_ATTEMPTS && error.retryable()) {
                MineMate.LOGGER.warn("Planner request failed ({}), retrying once", error.getMessage());
                return delay(retryDelayMs(reply)).thenCompose(ignored -> send(body, attempt + 1));
            }
            return CompletableFuture.failedFuture(error);
        });
    }

    private Map<String, String> headers() {
        // No beta or version header is required by this endpoint.
        return Map.of("Content-Type", "application/json",
                "Authorization", "Bearer " + apiKey);
    }

    // ---- request ----------------------------------------------------------------------------

    /**
     * Field order is load-bearing: the model, instructions and tools are byte-stable across
     * requests and form the cached prefix, so everything volatile goes after them.
     */
    JsonObject requestBody(LlmPrompt prompt) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("instructions", prompt.system());
        root.add("tools", OpenAiToolSchemas.toOpenAiTools(prompt.toolSchemaJson()));
        // Required, not auto: every valid hosted reply carries at least one function call, whether
        // a world action or report_order_status, so a zero-call speech reply is stopped at the
        // provider boundary instead of burning tokens on something the parser must reject.
        root.addProperty("tool_choice", "required");
        // True on purpose: several function calls in one reply are what a multi-step plan is.
        root.addProperty("parallel_tool_calls", true);

        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", reasoningEffort);
        root.add("reasoning", reasoning);

        JsonObject text = new JsonObject();
        text.addProperty("verbosity", verbosity);
        root.add("text", text);

        root.addProperty("max_output_tokens", maxOutputTokens);
        // Single turn, so there is no reasoning state worth having the provider keep.
        root.addProperty("store", false);
        if (!prompt.cacheKey().isEmpty()) {
            root.addProperty("prompt_cache_key", prompt.cacheKey());
        }

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt.user());
        JsonArray input = new JsonArray();
        input.add(message);
        root.add("input", input);
        // No temperature and no top_p: reasoning models reject or ignore them, and effort plus
        // verbosity are the supported knobs.
        return root;
    }

    // ---- response ---------------------------------------------------------------------------

    /** Public so the headless suite can assert on the parse directly, without a round trip. */
    public static LlmReply parseReply(String body) {
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (JsonSyntaxException error) {
            throw LlmException.malformed("reply was not JSON");
        }
        if (root == null || !root.isJsonObject()) {
            throw LlmException.malformed("reply was not a JSON object");
        }
        JsonElement output = root.getAsJsonObject().get("output");
        if (output == null || !output.isJsonArray()) {
            throw LlmException.malformed("reply had no output array");
        }

        StringBuilder dialogue = new StringBuilder();
        List<PlannerProtocol.RawCall> calls = new ArrayList<>();
        boolean sawMessage = false;
        for (JsonElement element : output.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            switch (string(item, "type")) {
                case "message" -> {
                    sawMessage = true;
                    appendText(item, dialogue);
                }
                case "function_call" -> calls.add(rawCallOf(item));
                // "reasoning" and anything OpenAI adds later: ignoring unknown item types keeps a
                // future addition from breaking planning outright.
                default -> {
                }
            }
        }
        return PlannerProtocol.assemble(dialogue.toString(), sawMessage, calls);
    }

    /**
     * Unwraps one function call into the neutral shape: the arguments arrive as a JSON string to
     * re-parse, and world-call arguments get their strict-mode nulls stripped. Protocol calls keep
     * theirs, because their validators reject a null field with a more precise message than an
     * absent one.
     */
    private static PlannerProtocol.RawCall rawCallOf(JsonObject call) {
        String name = string(call, "name");
        boolean protocol = PlannerProtocol.STATUS_TOOL.equals(name)
                || PlannerProtocol.CONTRACT_TOOL.equals(name);
        JsonObject arguments = argumentsOf(call, name.isBlank() ? "call" : name, !protocol);
        return new PlannerProtocol.RawCall(name, protocol ? arguments : withoutNulls(arguments));
    }

    private static void appendText(JsonObject message, StringBuilder dialogue) {
        JsonElement content = message.get("content");
        if (content == null || !content.isJsonArray()) {
            return;
        }
        for (JsonElement element : content.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject part = element.getAsJsonObject();
            if ("output_text".equals(string(part, "type"))) {
                if (!dialogue.isEmpty()) {
                    dialogue.append(' ');
                }
                dialogue.append(string(part, "text"));
            }
        }
    }

    private static JsonObject argumentsOf(JsonObject call, String name, boolean emptyAllowed) {
        String arguments = string(call, "arguments");
        if (arguments.isBlank()) {
            if (emptyAllowed) {
                return new JsonObject();
            }
            throw LlmException.malformed("arguments for " + name + " were missing");
        }
        JsonElement parsed;
        try {
            // Not a nested object on the wire: arguments arrive as a JSON string to re-parse.
            parsed = JsonParser.parseString(arguments);
        } catch (JsonSyntaxException error) {
            throw LlmException.malformed("arguments for " + name + " were not JSON");
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw LlmException.malformed("arguments for " + name + " were not an object");
        }
        return parsed.getAsJsonObject();
    }

    /**
     * Drops null-valued arguments.
     *
     * <p>Strict mode has no way to mark a parameter optional, so every optional one is declared
     * nullable and required, and the model duly sends {@code "count": null} when it has no opinion.
     * {@code ToolRegistry} rejects a null outright, but treats an <em>absent</em> key as the
     * parameter's default. Removing the key is therefore what turns a well-formed strict reply into
     * the call the registry was designed to accept; without this, every plan touching a defaulted
     * parameter is rejected as invalid_argument.
     *
     * <p>Shallow by design: tool arguments are flat, and a block position is an array of numbers
     * rather than an object, so there is no nesting to recurse into.
     */
    private static JsonObject withoutNulls(JsonObject arguments) {
        JsonObject cleaned = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : arguments.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isJsonNull()) {
                cleaned.add(entry.getKey(), entry.getValue());
            }
        }
        return cleaned;
    }

    // ---- errors -----------------------------------------------------------------------------

    /** Parses the standard error envelope defensively; any field may be missing or the wrong type. */
    private static LlmException errorOf(HttpTransport.HttpReply reply) {
        String message = "request failed";
        String code = "";
        try {
            JsonElement root = JsonParser.parseString(reply.body());
            if (root != null && root.isJsonObject()) {
                JsonElement error = root.getAsJsonObject().get("error");
                if (error != null && error.isJsonObject()) {
                    JsonObject details = error.getAsJsonObject();
                    String parsed = string(details, "message");
                    if (!parsed.isBlank()) {
                        message = parsed;
                    }
                    code = string(details, "code");
                }
            }
        } catch (JsonSyntaxException ignored) {
            // A gateway or proxy can answer with HTML. The status is still the useful part.
        }
        return new LlmException(reply.status(), code, message);
    }

    private static long retryDelayMs(HttpTransport.HttpReply reply) {
        long seconds = reply.header("Retry-After")
                .map(value -> {
                    try {
                        return Long.parseLong(value.strip());
                    } catch (NumberFormatException error) {
                        return 1L;
                    }
                })
                .orElse(1L);
        return Math.min(Math.max(seconds, 0L) * 1000L, MAX_RETRY_DELAY_MS);
    }

    private static CompletableFuture<Void> delay(long millis) {
        if (millis <= 0L) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
        }, CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS));
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static String string(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static String trimTrailingSlash(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
