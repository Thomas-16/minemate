package io.github.thomas16.minemate.context;

import java.util.Objects;

/**
 * One remembered event, stamped with the game tick it happened on.
 *
 * @param summary short ASCII phrase for the planner snapshot
 * @param detail  longer machine-ish text for logs; never rendered into the snapshot
 */
public record JournalEntry(long gameTime, EventKind kind, String summary, String detail) {

    private static final int MAX_SUMMARY = 90;

    public JournalEntry {
        Objects.requireNonNull(kind, "kind");
        summary = summary == null ? "" : summary;
        detail = detail == null ? "" : detail;
    }

    public static JournalEntry of(long gameTime, EventKind kind, String summary) {
        return new JournalEntry(gameTime, kind, summary, "");
    }

    /** Relative age plus summary, e.g. {@code " -1m18s took 4 damage from zombie"}. ASCII only. */
    public String render(long nowGameTime) {
        return " -" + age(Math.max(0L, nowGameTime - gameTime)) + " " + bounded(summary);
    }

    private static String age(long ticks) {
        long seconds = ticks / 20L;
        if (seconds < 60L) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        return minutes < 60L
                ? "%dm%02ds".formatted(minutes, seconds % 60L)
                : "%dh%02dm".formatted(minutes / 60L, minutes % 60L);
    }

    private static String bounded(String value) {
        return value.length() <= MAX_SUMMARY
                ? value : value.substring(0, MAX_SUMMARY - 3) + "...";
    }
}
