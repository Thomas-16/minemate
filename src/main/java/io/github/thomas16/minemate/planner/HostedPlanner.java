package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.planner.llm.AnthropicMessagesBackend;
import io.github.thomas16.minemate.planner.llm.JdkHttpTransport;
import io.github.thomas16.minemate.planner.llm.LlmBackend;
import io.github.thomas16.minemate.planner.llm.LlmException;
import io.github.thomas16.minemate.planner.llm.LlmPrompt;
import io.github.thomas16.minemate.planner.llm.LlmReply;
import io.github.thomas16.minemate.planner.llm.OpenAiResponsesBackend;
import io.github.thomas16.minemate.planner.llm.PlannerPrompt;
import io.github.thomas16.minemate.planner.llm.PlannerProtocol;
import io.github.thomas16.minemate.tool.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The stage-5 planner: asks a hosted model what to do and turns its answer into a {@link Plan}.
 *
 * <p>It adds no authority. Everything it produces still goes through {@code ToolRegistry.validate}
 * before a single step reaches the supervisor, so the model chooses among already-safe tools and
 * nothing else. Failure is ordinary here rather than exceptional: a timeout, a malformed reply and
 * a refusal all end as a failed future, which {@link PlannerDispatch} reports to the owner and
 * forgets.
 */
public final class HostedPlanner implements Planner {

    private final LlmBackend backend;
    private final PlannerConfig config;

    /** Derived from the prompt prefix, so adding a tool invalidates the provider's cache for us. */
    private volatile String cachedSchema = "";
    private volatile String cacheKey = "";

    public HostedPlanner(LlmBackend backend, PlannerConfig config) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * The ordinary construction path: a real HTTP transport wired from config. The provider switch
     * lives here and nowhere else; everything above this line speaks {@link LlmBackend}.
     */
    public static HostedPlanner of(PlannerConfig config) {
        LlmBackend backend = switch (config.provider()) {
            case PlannerConfig.PROVIDER_OPENAI -> new OpenAiResponsesBackend(
                    new JdkHttpTransport(config.timeout()), config.baseUrl(), config.model(),
                    config.apiKey().orElse(""), config.reasoningEffort(), config.verbosity(),
                    config.maxOutputTokens());
            case PlannerConfig.PROVIDER_ANTHROPIC -> new AnthropicMessagesBackend(
                    new JdkHttpTransport(config.timeout()), config.baseUrl(), config.model(),
                    config.apiKey().orElse(""), config.reasoningEffort(),
                    config.maxOutputTokens());
            // Unreachable through installPlanner, which refuses an unusable config; loud for any
            // future caller that skips that check.
            default -> throw new IllegalArgumentException(
                    "unknown planner provider: " + config.provider());
        };
        return new HostedPlanner(backend, config);
    }

    @Override
    public String name() {
        return backend.name();
    }

    public PlannerConfig config() {
        return config;
    }

    @Override
    public boolean requiresContract() {
        return true;
    }

    @Override
    public CompletableFuture<Plan> plan(PlanRequest request) {
        String hostedSchema = PlannerProtocol.hostedSchema(request.toolSchemaJson());
        LlmPrompt prompt = new LlmPrompt(PlannerPrompt.SYSTEM, PlannerPrompt.user(request),
                hostedSchema, cacheKeyFor(hostedSchema));
        // The backend returns immediately; the mapping below runs on whatever thread completes it,
        // and touches nothing but the reply. PlannerDispatch owns the hop back to the server.
        //
        // The timeout is enforced here as well as in the transport on purpose. A request that never
        // completes would leave isPlannerRequestPending() true forever, which silently disables
        // replanning for that companion for the rest of the session; a backend that forgets its own
        // deadline must not be able to cause that.
        return backend.complete(prompt)
                .orTimeout(config.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .thenApply(reply -> toPlan(request, reply));
    }

    @Override
    public void close() {
        backend.close();
    }

    private Plan toPlan(PlanRequest request, LlmReply reply) {
        List<ToolCall> calls = reply.calls();
        if (calls.size() > Plan.MAX_STEPS) {
            throw LlmException.malformed("planner returned more than " + Plan.MAX_STEPS
                    + " world-tool calls");
        }
        // A contract with no world calls passes through as an empty ACTION plan: dispatch owns
        // that case, because its remedy is a repair request, not a parse failure.
        if (reply.disposition() == PlanDisposition.ACTION
                && (reply.dialogue().isBlank() || (calls.isEmpty() && reply.contract() == null))) {
            throw LlmException.malformed("action reply requires speech and at least one world-tool call");
        }
        if (reply.disposition().terminal()
                && (!calls.isEmpty() || reply.dialogue().isBlank())) {
            throw LlmException.malformed("terminal reply requires one status message and no world-tool calls");
        }
        if (reply.disposition().terminal() && reply.contract() != null) {
            throw LlmException.malformed("terminal reply must not declare an order contract");
        }
        List<PlanStep> steps = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            steps.add(PlanStep.of(call));
        }
        return new Plan(Plan.nextId(), request.requestText(), steps, reply.dialogue(),
                reply.disposition(), reply.contract());
    }

    private String cacheKeyFor(String schemaJson) {
        String schema = schemaJson == null ? "" : schemaJson;
        if (!schema.equals(cachedSchema) || cacheKey.isEmpty()) {
            cacheKey = "minemate-" + Integer.toHexString(Objects.hash(PlannerPrompt.SYSTEM, schema));
            cachedSchema = schema;
        }
        return cacheKey;
    }
}
