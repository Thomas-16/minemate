package io.github.thomas16.minemate.dev;

import io.github.thomas16.minemate.planner.llm.HttpTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A canned {@link HttpTransport} for the headless suite.
 *
 * <p>Substituting here rather than one layer up is the point of the seam: every scenario still runs
 * the real request builder and the real response parser, which is where a provider integration
 * actually goes wrong. A fake backend would prove only that the plan executor works, which stage 4
 * already established.
 *
 * <p>Replies are consumed in order and the last one repeats, so a one-element fixture answers every
 * request identically.
 */
public final class FixtureTransport implements HttpTransport {

    private final List<HttpReply> replies;
    private final List<String> requestBodies = new ArrayList<>();
    private final long delayMs;
    private final boolean neverCompletes;

    private FixtureTransport(List<HttpReply> replies, long delayMs, boolean neverCompletes) {
        this.replies = List.copyOf(replies);
        this.delayMs = delayMs;
        this.neverCompletes = neverCompletes;
    }

    public static FixtureTransport of(HttpReply... replies) {
        return new FixtureTransport(List.of(replies), 0L, false);
    }

    /** Answers only after a delay, so a scenario can cancel while a request is genuinely in flight. */
    public static FixtureTransport delayed(long delayMs, HttpReply... replies) {
        return new FixtureTransport(List.of(replies), delayMs, false);
    }

    /** Never answers at all: the case a planner's own deadline has to survive. */
    public static FixtureTransport hanging() {
        return new FixtureTransport(List.of(), 0L, true);
    }

    public static HttpReply ok(String body) {
        return new HttpReply(200, body);
    }

    public static HttpReply error(int status, String code, String message) {
        return new HttpReply(status, "{\"error\":{\"message\":\"" + message
                + "\",\"type\":\"invalid_request_error\",\"code\":\"" + code + "\"}}");
    }

    public static HttpReply rateLimited(String retryAfterSeconds) {
        return new HttpReply(429, "{\"error\":{\"message\":\"slow down\",\"code\":\"rate_limit_exceeded\"}}",
                Map.of("Retry-After", retryAfterSeconds));
    }

    @Override
    public CompletableFuture<HttpReply> post(String url, Map<String, String> headers, String body) {
        synchronized (requestBodies) {
            requestBodies.add(body);
        }
        if (neverCompletes) {
            return new CompletableFuture<>();
        }
        HttpReply reply = replies.get(Math.min(calls() - 1, replies.size() - 1));
        if (delayMs <= 0L) {
            return CompletableFuture.completedFuture(reply);
        }
        return CompletableFuture.supplyAsync(() -> reply,
                CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS));
    }

    /** How many requests were made. The assertion that most budget scenarios turn on. */
    public int calls() {
        synchronized (requestBodies) {
            return requestBodies.size();
        }
    }

    public String lastRequestBody() {
        synchronized (requestBodies) {
            return requestBodies.isEmpty() ? "" : requestBodies.get(requestBodies.size() - 1);
        }
    }

    public void reset() {
        synchronized (requestBodies) {
            requestBodies.clear();
        }
    }
}
