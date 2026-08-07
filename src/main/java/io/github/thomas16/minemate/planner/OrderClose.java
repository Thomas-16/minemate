package io.github.thomas16.minemate.planner;

import java.util.Objects;

/**
 * A request to close the current standing order.
 *
 * @param fallbackMessage short owner-facing line used when the reply carried no assistant speech;
 *                        the displayed line is the response's speech when that is nonblank
 */
public record OrderClose(OrderCloseStatus status, String fallbackMessage) {

    public OrderClose {
        Objects.requireNonNull(status, "status");
        fallbackMessage = fallbackMessage == null ? "" : fallbackMessage.strip();
    }
}
