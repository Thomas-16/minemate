package io.github.thomas16.minemate.tool;

import io.github.thomas16.minemate.task.Reasons;

import java.util.Objects;

/**
 * Thrown by a {@link ToolDescriptor.GoalBuilder} when a schema-valid call cannot be bound to the
 * world right now — the kill target despawned, the follow player logged out, the container is gone.
 *
 * <p>Distinct from a validation rejection: the call itself is well formed, so the reason is a
 * world-state {@link Reasons} token rather than an argument complaint.
 */
public final class ToolBindException extends RuntimeException {

    private final String reason;

    public ToolBindException(String reason, String detail) {
        super(detail == null ? reason : detail);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** A machine-readable {@link Reasons} token. */
    public String reason() {
        return reason;
    }
}
