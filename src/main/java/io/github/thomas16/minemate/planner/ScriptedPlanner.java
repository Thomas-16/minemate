package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.task.Reasons;
import io.github.thomas16.minemate.tool.ToolCall;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Stands in for the stage-5 LLM: returns canned responses keyed by request text.
 *
 * <p>It completes on its own single-thread executor even with no delay configured, so the
 * {@code server.execute} hop in {@link PlannerDispatch} is genuinely exercised rather than
 * short-circuited by a response that was already available.
 */
public final class ScriptedPlanner implements Planner {

    private static final ScriptedPlanner INSTANCE = new ScriptedPlanner();

    private final Map<String, BiFunction<CompanionEntity, PlanRequest, CompanionResponse>> scripts =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "minemate-scripted-planner");
        thread.setDaemon(true);
        return thread;
    });

    private volatile CompanionEntity companion;
    private volatile long delayMs;
    private volatile int requestCount;

    private ScriptedPlanner() {
    }

    public static ScriptedPlanner instance() {
        return INSTANCE;
    }

    @Override
    public String name() {
        return "scripted";
    }

    /**
     * Registers a response built against live fixtures, e.g. a scenario's container position.
     *
     * <p>The builder runs on the planner's own thread, so it must read the immutable
     * {@link PlanRequest} snapshot rather than touching the companion or the level directly.
     */
    public void script(String requestKey,
            BiFunction<CompanionEntity, PlanRequest, CompanionResponse> response) {
        scripts.put(key(requestKey), Objects.requireNonNull(response, "response"));
    }

    /**
     * Registers an action response as raw planner JSON: an array of {@code {tool, arguments}}
     * objects.
     *
     * <p>This is the highest-value form to cover, because it exercises the exact parse and
     * validation path a stage-5 model's output will take.
     */
    public void scriptRaw(String requestKey, String toolCallsJson) {
        script(requestKey, (entity, request) -> {
            List<ToolCall> calls = ToolCall.parseList(toolCallsJson);
            if (calls == null) {
                throw new IllegalStateException("scripted response JSON did not parse");
            }
            return CompanionResponse.actions("", calls, null);
        });
    }

    /** The companion a scripted response is built for; set by the request path before planning. */
    public void bind(CompanionEntity companion) {
        this.companion = companion;
    }

    public void setDelayMs(long delayMs) {
        this.delayMs = Math.max(0L, delayMs);
    }

    public int requestCount() {
        return requestCount;
    }

    public void clear() {
        scripts.clear();
        requestCount = 0;
        delayMs = 0L;
    }

    @Override
    public CompletableFuture<CompanionResponse> plan(PlanRequest request) {
        requestCount++;
        CompanionEntity target = companion;
        BiFunction<CompanionEntity, PlanRequest, CompanionResponse> script =
                scripts.get(key(request.requestText()));
        CompletableFuture<CompanionResponse> future = new CompletableFuture<>();
        executor.schedule(() -> {
            if (script == null) {
                future.completeExceptionally(new IllegalStateException(Reasons.PLANNER_UNAVAILABLE));
                return;
            }
            try {
                future.complete(script.apply(target, request));
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        return future;
    }

    private static String key(String requestText) {
        return requestText == null ? "" : requestText.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * A few fixed responses so {@code /companion plan} is exercisable in a real client before
     * stage 5 exists. Placeholders for a model, not a feature: the last two are deliberately
     * invalid and exist to show the rejection paths.
     */
    public static void installDemoScripts() {
        ScriptedPlanner planner = instance();

        planner.script("gather wood", (companion, request) -> CompanionResponse.actions(
                "On it - getting wood and turning it into planks.",
                List.of(ToolCall.of("mine").with("resource", "oak_log").with("count", 3),
                        ToolCall.of("craft").with("item", "minecraft:oak_planks").with("count", 8)),
                null));

        planner.script("come here", (companion, request) -> CompanionResponse.actions(
                "Coming to you.", List.of(ToolCall.of("come_to_owner")), null));

        // A continuous tool is legal as the final step, and the plan then stays open-ended.
        planner.script("stay with me", (companion, request) -> CompanionResponse.actions(
                "I'll stick close.", List.of(ToolCall.of("follow").with("distance", 3.0)), null));

        // Rejected at validation: the resource is not in the catalogue, so no step ever runs.
        planner.scriptRaw("bad plan", "["
                + "{\"tool\":\"mine\",\"arguments\":{\"resource\":\"dirt\",\"count\":1}},"
                + "{\"tool\":\"mine\",\"arguments\":{\"resource\":\"unobtainium\",\"count\":1}}]");

        // Rejected too: a continuous tool anywhere but last would strand every later step.
        planner.scriptRaw("impossible order", "["
                + "{\"tool\":\"follow\",\"arguments\":{}},"
                + "{\"tool\":\"mine\",\"arguments\":{\"resource\":\"dirt\",\"count\":1}}]");
    }
}
