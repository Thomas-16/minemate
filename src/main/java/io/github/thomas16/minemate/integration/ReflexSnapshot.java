package io.github.thomas16.minemate.integration;

import java.util.Objects;

/** Immutable current-state telemetry for PlayerEngine's automatic reflex chains. */
public record ReflexSnapshot(ReflexKind kind, String chain, long startedAtTick,
        boolean defenseSuppressed, double ownerDistance, String detail) {

    public ReflexSnapshot {
        Objects.requireNonNull(kind, "kind");
        chain = chain == null ? "" : chain;
        detail = detail == null ? "" : detail;
    }

    public boolean active() {
        return kind != ReflexKind.NONE;
    }
}
