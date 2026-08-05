package io.github.thomas16.minemate.planner.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** The real transport: JDK {@link HttpClient}, no dependency added. */
public final class JdkHttpTransport implements HttpTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Two is plenty: a companion has at most one planner request in flight, plus a retry. */
    private static final int THREADS = 2;

    private final ExecutorService executor;
    private final HttpClient client;
    private final Duration requestTimeout;

    public JdkHttpTransport(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        AtomicInteger counter = new AtomicInteger();
        // Daemon threads on purpose: a half-finished planner call must never hold the server open.
        this.executor = Executors.newFixedThreadPool(THREADS, runnable -> {
            Thread thread = new Thread(runnable, "minemate-llm-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(executor)
                .build();
    }

    @Override
    public CompletableFuture<HttpReply> post(String url, Map<String, String> headers, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return client.sendAsync(builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response ->
                        new HttpReply(response.statusCode(), response.body(), firstValues(response)));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /** Only the first value of each header is kept; nothing here reads a repeated header. */
    private static Map<String, String> firstValues(HttpResponse<String> response) {
        Map<String, String> headers = new HashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        return headers;
    }
}
