package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.planner.llm.JdkHttpTransport;
import io.github.thomas16.minemate.planner.llm.LlmBackend;
import io.github.thomas16.minemate.planner.llm.LlmPrompt;
import io.github.thomas16.minemate.planner.llm.LlmReply;
import io.github.thomas16.minemate.planner.llm.OpenAiResponsesBackend;
import io.github.thomas16.minemate.planner.llm.PlannerPrompt;
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

    /** The ordinary construction path: a real HTTP transport wired from config. */
    public static HostedPlanner of(PlannerConfig config) {
        LlmBackend backend = new OpenAiResponsesBackend(new JdkHttpTransport(config.timeout()),
                config.baseUrl(), config.model(), config.apiKey().orElse(""),
                config.reasoningEffort(), config.verbosity(), config.maxOutputTokens());
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
    public CompletableFuture<Plan> plan(PlanRequest request) {
        LlmPrompt prompt = new LlmPrompt(PlannerPrompt.SYSTEM, PlannerPrompt.user(request),
                request.toolSchemaJson(), cacheKeyFor(request.toolSchemaJson()));
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
            // Trimming from the end keeps the plan valid: a continuous tool can only have been last,
            // so dropping the tail can never strand one in the middle.
            MineMate.LOGGER.warn("Planner returned {} calls; keeping the first {}",
                    calls.size(), Plan.MAX_STEPS);
            calls = calls.subList(0, Plan.MAX_STEPS);
        }
        List<PlanStep> steps = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            steps.add(PlanStep.of(call));
        }
        // Zero steps with dialogue is the model reporting it is done; dispatch treats that as speech.
        return new Plan(Plan.nextId(), request.requestText(), steps, reply.dialogue());
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
