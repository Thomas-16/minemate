package io.github.thomas16.minemate.planner;

import java.util.concurrent.CompletableFuture;

/**
 * Turns a request plus world context into a bounded plan.
 *
 * <p>Stage 4 ships only a scripted implementation; stage 5 adds a hosted LLM behind the same
 * interface.
 */
public interface Planner {

    String name();

    /**
     * Called <em>on the server thread</em> and must return immediately. Every implementation does
     * its work on its own executor: one blocking call here freezes the whole server for the length
     * of a network round trip. Route completions back through {@link PlannerDispatch}, which is the
     * only place allowed to hop back onto the server thread.
     */
    CompletableFuture<Plan> plan(PlanRequest request);

    /**
     * Whether this planner speaks the order-contract protocol: its first action reply for an order
     * must declare an {@link OrderContract}, which dispatch validates the plan against. Scripted
     * planners do not, and are never contract-checked.
     */
    default boolean requiresContract() {
        return false;
    }

    default void close() {
    }
}
