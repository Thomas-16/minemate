package io.github.thomas16.minemate.planner.llm;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The seam between a backend and the network.
 *
 * <p>It exists so the headless suite can drive the real request builder and the real response
 * parser with canned bytes. A fake {@link LlmBackend} would be easier to write and would prove
 * nothing: the parts most likely to be wrong are exactly the JSON shapes on either side of this
 * interface, and they are the parts a backend-level fake skips.
 */
public interface HttpTransport {

    /**
     * One reply. The body is read as text whatever the status, because a provider's error detail
     * lives in the body of a non-2xx response and is the only useful thing to log.
     */
    record HttpReply(int status, String body, Map<String, String> headers) {

        public HttpReply(int status, String body) {
            this(status, body, Map.of());
        }

        public HttpReply {
            body = body == null ? "" : body;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** Header names are case-insensitive on the wire, so callers must not have to guess the case. */
        public Optional<String> header(String name) {
            String wanted = name.toLowerCase(Locale.ROOT);
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(wanted))
                    .map(Map.Entry::getValue)
                    .findFirst();
        }
    }

    /**
     * Sends one POST. Must return immediately: this is reached from the server thread by way of
     * {@link io.github.thomas16.minemate.planner.PlannerDispatch}, and a blocking call here would
     * freeze the tick for a whole network round trip.
     */
    CompletableFuture<HttpReply> post(String url, Map<String, String> headers, String body);

    default void close() {
    }
}
