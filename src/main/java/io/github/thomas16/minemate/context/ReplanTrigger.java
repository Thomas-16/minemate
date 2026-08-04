package io.github.thomas16.minemate.context;

import java.util.Objects;

/** A fired replan signal: why, when, and a short ASCII detail for logs and prompts. */
public record ReplanTrigger(ReplanReason reason, long gameTime, String detail) {

    public ReplanTrigger {
        Objects.requireNonNull(reason, "reason");
        detail = detail == null ? "" : detail;
    }
}
