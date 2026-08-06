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
 * Anthropic Messages API backend.
 *
 * <p>The wire mapping is thinner than OpenAI's on purpose. Anthropic declares a tool as
 * {@code {name, description, input_schema}}, which is byte-for-byte the registry's neutral shape,
 * so the schema ships verbatim: no strict transform, no null unions, and therefore no null
 * stripping on the way back. Strict mode is deliberately not requested, because Anthropic's strict
 * schema engine rejects {@code minimum}/{@code maximum} and {@code ToolRegistry} re-validates every
 * argument deterministically anyway.
 *
 * <p>Three reply details are easy to get wrong. {@code content} is a typed block array that
 * normally leads with a {@code thinking} block, so it must be filtered by {@code type} and never
 * indexed. {@code tool_use.input} arrives as an already-parsed JSON object, not a string. And a
 * truncated reply announces itself only through {@code stop_reason: "max_tokens"}, so that field
 * has to be checked before trusting the blocks.
 *
 * <p>Prompt caching is explicit here rather than implicit: one {@code cache_control} breakpoint
 * caches tools plus shared system rules, and a second caches the stable per-companion identity.
 * {@code LlmPrompt.cacheKey} is unused; Anthropic matches prefixes automatically.
 */
public final class AnthropicMessagesBackend implements LlmBackend {

    private static final String PATH = "/messages";
    private static final String API_VERSION = "2023-06-01";
    /** One retry only. A planner request the owner is waiting on is not worth a long backoff chain. */
    private static final int MAX_ATTEMPTS = 2;
    private static final long MAX_RETRY_DELAY_MS = 5_000L;

    private static volatile String cachedSchemaInput;
    private static volatile JsonArray cachedTools;

    private final HttpTransport transport;
    private final String url;
    private final String model;
    private final String apiKey;
    private final String reasoningEffort;
    private final int maxOutputTokens;

    public AnthropicMessagesBackend(HttpTransport transport, String baseUrl, String model,
            String apiKey, String reasoningEffort, int maxOutputTokens) {
        this.transport = transport;
        this.url = trimTrailingSlash(baseUrl) + PATH;
        this.model = model;
        this.apiKey = apiKey;
        this.reasoningEffort = reasoningEffort;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public String name() {
        return "anthropic:" + model;
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
        // which lives in a header.
        MineMate.LOGGER.debug("Planner request {}: {}", attempt, body);
        return transport.post(url, headers(), body).thenCompose(reply -> {
            MineMate.LOGGER.debug("Planner reply {}: {}", reply.status(), reply.body());
            if (reply.ok()) {
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
        // x-api-key, not Authorization: Bearer; the version header is mandatory on this endpoint.
        return Map.of("Content-Type", "application/json",
                "x-api-key", apiKey,
                "anthropic-version", API_VERSION);
    }

    // ---- request ----------------------------------------------------------------------------

    /**
     * Field order mirrors the cached prefix: tools then system are the reusable prefix that
     * {@code cache_control} snapshots, and the volatile user message goes last. The {@code thinking}
     * parameter is omitted on purpose -- the model's default (adaptive on the 5-series) is the only
     * shape valid across every current Claude model -- and {@code output_config.effort} carries the
     * configured reasoning effort instead.
     */
    JsonObject requestBody(LlmPrompt prompt) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("max_tokens", maxOutputTokens);
        root.add("tools", toolsOf(prompt.toolSchemaJson()));
        // "auto", not "any". "any" would be the analog of OpenAI's "required", but forcing tool use
        // suppresses the assistant text block, and a live claude-sonnet-5 reply duly arrived as
        // world calls with no speech -- which the protocol must reject. The system prompt already
        // demands a call in every reply, so the prompt does the forcing and the text survives.
        // Parallel tool use is Anthropic's default, so there is no parallel_tool_calls knob to set.
        JsonObject toolChoice = new JsonObject();
        toolChoice.addProperty("type", "auto");
        root.add("tool_choice", toolChoice);

        JsonArray system = new JsonArray();
        // Two explicit writes matter. A request for another companion misses at the identity block
        // but can walk back to the shared rules entry; repeated requests for one companion hit the
        // complete tools + rules + identity prefix.
        system.add(cachedSystemBlock(prompt.system()));
        system.add(cachedSystemBlock(prompt.systemIdentity()));
        root.add("system", system);

        if (!reasoningEffort.isBlank()) {
            JsonObject outputConfig = new JsonObject();
            outputConfig.addProperty("effort", reasoningEffort);
            root.add("output_config", outputConfig);
        }

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt.user());
        JsonArray messages = new JsonArray();
        messages.add(message);
        root.add("messages", messages);
        // No temperature and no top_p: the 5-series rejects them outright.
        return root;
    }

    /** Default ephemeral cache control intentionally retains Anthropic's five-minute TTL. */
    private static JsonObject cachedSystemBlock(String text) {
        JsonObject cacheControl = new JsonObject();
        cacheControl.addProperty("type", "ephemeral");
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        block.add("cache_control", cacheControl);
        return block;
    }

    /** The registry's neutral schema is already Anthropic's tool shape; parse it once and reuse. */
    private static JsonArray toolsOf(String schemaJson) {
        String input = schemaJson == null ? "" : schemaJson;
        JsonArray cached = cachedTools;
        if (cached != null && input.equals(cachedSchemaInput)) {
            return cached.deepCopy();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(input);
        } catch (JsonSyntaxException error) {
            throw LlmException.malformed("tool schema did not parse");
        }
        if (root == null || !root.isJsonArray()) {
            throw LlmException.malformed("tool schema was not an array");
        }
        JsonArray tools = root.getAsJsonArray();
        cachedSchemaInput = input;
        cachedTools = tools;
        return tools.deepCopy();
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
        JsonObject reply = root.getAsJsonObject();
        String stopReason = string(reply, "stop_reason");
        if ("max_tokens".equals(stopReason)) {
            throw LlmException.malformed("reply was truncated at max_tokens; raise maxOutputTokens");
        }
        if ("refusal".equals(stopReason)) {
            throw LlmException.malformed("the model declined to answer");
        }
        JsonElement content = reply.get("content");
        if (content == null || !content.isJsonArray()) {
            throw LlmException.malformed("reply had no content array");
        }

        StringBuilder dialogue = new StringBuilder();
        List<PlannerProtocol.RawCall> calls = new ArrayList<>();
        boolean sawText = false;
        for (JsonElement element : content.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            switch (string(block, "type")) {
                case "text" -> {
                    sawText = true;
                    if (!dialogue.isEmpty()) {
                        dialogue.append(' ');
                    }
                    dialogue.append(string(block, "text"));
                }
                case "tool_use" -> calls.add(rawCallOf(block));
                // "thinking", "redacted_thinking" and anything Anthropic adds later: ignoring
                // unknown block types keeps a future addition from breaking planning outright.
                default -> {
                }
            }
        }
        return PlannerProtocol.assemble(dialogue.toString(), sawText, calls);
    }

    /** {@code input} is an object on the wire, unlike OpenAI's string; absent means no arguments. */
    private static PlannerProtocol.RawCall rawCallOf(JsonObject block) {
        String name = string(block, "name");
        JsonElement input = block.get("input");
        if (input != null && !input.isJsonNull() && !input.isJsonObject()) {
            throw LlmException.malformed("arguments for " + (name.isBlank() ? "call" : name)
                    + " were not an object");
        }
        JsonObject arguments = input != null && input.isJsonObject()
                ? input.getAsJsonObject().deepCopy()
                : new JsonObject();
        return new PlannerProtocol.RawCall(name, arguments);
    }

    // ---- errors -----------------------------------------------------------------------------

    /**
     * Parses the Anthropic error envelope {@code {"type":"error","error":{"type","message"}}}
     * defensively; any field may be missing or the wrong type. The inner {@code type} doubles as
     * the code, which is what makes 529 overloaded_error and the billing codes recognisable.
     */
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
                    code = string(details, "type");
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
