package io.github.thomas16.minemate.task;

/** How much work a goal may delegate to PlayerEngine beneath its public verb. */
public enum ExecutionScope {
    /** A bounded operation whose name closely describes its engine work. */
    DIRECT,
    /** A high-level acquisition goal that may compose tools, recipes, travel, and combat. */
    COMPOSITE,
    /** Open-ended work that remains active until cancelled or superseded. */
    CONTINUOUS
}
