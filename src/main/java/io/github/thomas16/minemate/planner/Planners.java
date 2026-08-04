package io.github.thomas16.minemate.planner;

import java.util.Objects;

/** Holds the planner in use. Stage 5 installs a hosted one with a single call. */
public final class Planners {

    private static Planner active = ScriptedPlanner.instance();

    private Planners() {
    }

    public static Planner active() {
        return active;
    }

    public static void install(Planner planner) {
        active = Objects.requireNonNull(planner, "planner");
    }
}
