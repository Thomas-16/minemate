package io.github.thomas16.minemate.context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * A bounded, newest-last ring of recent events, one per companion. Server-thread only.
 *
 * <p>Coalescing is load-bearing, not a nicety. A {@code mine 64 cobblestone} goal produces a pickup
 * every few ticks; without merging them a 64-entry ring holds nothing but pickups and every goal and
 * damage event has already been evicted by the time a planner reads it.
 */
public final class CompanionJournal {

    public static final int CAPACITY = 64;

    private static final int PICKUP_COALESCE_TICKS = 60;
    private static final int DAMAGE_COALESCE_TICKS = 20;

    private final Deque<JournalEntry> entries = new ArrayDeque<>();

    public void record(JournalEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (coalesce(entry)) {
            return;
        }
        entries.addLast(entry);
        while (entries.size() > CAPACITY) {
            entries.removeFirst();
        }
    }

    public void record(long gameTime, EventKind kind, String summary) {
        record(JournalEntry.of(gameTime, kind, summary));
    }

    /**
     * Merges a repeated pickup or damage event into the newest entry rather than appending.
     * Coalescing only ever considers the newest entry, so an interleaved event of another kind
     * correctly ends the run.
     */
    private boolean coalesce(JournalEntry entry) {
        JournalEntry newest = entries.peekLast();
        if (newest == null || newest.kind() != entry.kind()) {
            return false;
        }
        int window = switch (entry.kind()) {
            case ITEM_PICKED_UP -> PICKUP_COALESCE_TICKS;
            case DAMAGE_TAKEN -> DAMAGE_COALESCE_TICKS;
            default -> 0;
        };
        if (window == 0 || entry.gameTime() - newest.gameTime() > window) {
            return false;
        }
        // detail() carries the coalescing key: the item id, or the damage source name.
        if (!newest.detail().equals(entry.detail())) {
            return false;
        }
        entries.removeLast();
        entries.addLast(new JournalEntry(entry.gameTime(), entry.kind(),
                merge(newest, entry), entry.detail()));
        return true;
    }

    private static String merge(JournalEntry newest, JournalEntry entry) {
        return switch (entry.kind()) {
            case ITEM_PICKED_UP -> "picked up " + entry.detail() + " x"
                    + (countOf(newest.summary()) + countOf(entry.summary()));
            case DAMAGE_TAKEN -> "took " + "%.0f".formatted(damageOf(newest.summary())
                    + damageOf(entry.summary())) + " damage from " + entry.detail();
            default -> entry.summary();
        };
    }

    private static int countOf(String summary) {
        int marker = summary.lastIndexOf(" x");
        if (marker < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(summary.substring(marker + 2));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static double damageOf(String summary) {
        String[] parts = summary.split(" ");
        if (parts.length < 2) {
            return 0.0;
        }
        try {
            return Double.parseDouble(parts[1]);
        } catch (NumberFormatException error) {
            return 0.0;
        }
    }

    /** Newest first, at most {@code max} entries. */
    public List<JournalEntry> recent(int max) {
        List<JournalEntry> recent = new ArrayList<>(Math.min(max, entries.size()));
        Iterator<JournalEntry> newestFirst = entries.descendingIterator();
        while (newestFirst.hasNext() && recent.size() < max) {
            recent.add(newestFirst.next());
        }
        return List.copyOf(recent);
    }

    /** Oldest first, everything strictly after {@code gameTime}. */
    public List<JournalEntry> since(long gameTime) {
        List<JournalEntry> selected = new ArrayList<>();
        for (JournalEntry entry : entries) {
            if (entry.gameTime() > gameTime) {
                selected.add(entry);
            }
        }
        return List.copyOf(selected);
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }
}
