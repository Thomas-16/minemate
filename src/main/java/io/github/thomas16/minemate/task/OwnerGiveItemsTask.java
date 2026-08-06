package io.github.thomas16.minemate.task;

import com.player2.playerengine.tasks.base.Task;

import net.minecraft.world.item.Item;

import java.util.List;

/**
 * Delivers a list of final items to the owner one at a time, running a fresh
 * {@link OwnerGiveItemTask} per entry so each delivery gets its own owner baseline and the full
 * acquire-or-craft-then-deliver machinery. Best effort: an entry whose acquisition exhausts is
 * skipped and the rest still deliver; only a missing owner ends the whole task, because every
 * later delivery would fail the same way.
 *
 * <p>When every entry has been processed and any were skipped, the task idles rather than calling
 * {@code fail}: the goal's probe reports the structured blocked outcome within one sample, and
 * idling avoids racing the supervisor's generic engine-terminal mapping.
 */
class OwnerGiveItemsTask extends Task {

    /** One deliverable with its catalogue matches precomputed by {@link Goals#fetchItems}. */
    record FetchEntry(String item, int count, Item[] matches) {
    }

    private final List<FetchEntry> entries;
    private int index;
    private OwnerGiveItemTask child;
    private boolean childTicked;
    private boolean[] deliveredEntries;
    private String[] skippedReason;
    private boolean done;

    OwnerGiveItemsTask(List<FetchEntry> entries) {
        this.entries = List.copyOf(entries);
        // The probe may query this task before PlayerEngine first ticks it and runs onStart.
        this.deliveredEntries = new boolean[this.entries.size()];
        this.skippedReason = new String[this.entries.size()];
    }

    @Override
    protected void onStart() {
        index = 0;
        child = null;
        childTicked = false;
        deliveredEntries = new boolean[entries.size()];
        skippedReason = new String[entries.size()];
        done = false;
    }

    @Override
    protected Task onTick() {
        if (done) {
            return null;
        }
        if (index >= entries.size()) {
            // Exhausted with skips: idle and let the probe close the goal with the item report.
            setDebugState("Delivered %d of %d items; %d unavailable"
                    .formatted(deliveredCount(), entries.size(), skippedCount()));
            return null;
        }
        FetchEntry entry = entries.get(index);
        if (child == null) {
            child = new OwnerGiveItemTask(entry.item(), entry.count(), entry.matches());
            childTicked = false;
        }
        // Only after the runner has had it: PlayerEngine assigns a task's controller when it
        // first ticks it, and querying a freshly created task can dereference that controller.
        if (childTicked && (child.stopped() || child.isFinished())) {
            if (child.isFinished()) {
                // isFinished is true only once the owner's inventory confirmed the delivery.
                deliveredEntries[index] = true;
            } else {
                String failure = child.failureReason().orElse(Reasons.RESOURCE_UNAVAILABLE);
                if (Reasons.OWNER_OFFLINE.equals(failure)) {
                    fail(Reasons.OWNER_OFFLINE);
                    return null;
                }
                skippedReason[index] = failure;
            }
            index++;
            child = null;
            if (index >= entries.size() && skippedCount() == 0) {
                done = true;
                stop();
            }
            return null;
        }
        childTicked = true;
        setDebugState("Item %d/%d: %s".formatted(index + 1, entries.size(), entry.item()));
        return child;
    }

    /** The entry currently being worked, clamped to the last entry once all are processed. */
    int currentIndex() {
        return Math.min(index, entries.size() - 1);
    }

    boolean processedAll() {
        return index >= entries.size();
    }

    boolean anySkipped() {
        return skippedCount() > 0;
    }

    private int deliveredCount() {
        int count = 0;
        for (boolean delivered : deliveredEntries) {
            if (delivered) {
                count++;
            }
        }
        return count;
    }

    private int skippedCount() {
        int count = 0;
        for (String reason : skippedReason) {
            if (reason != null) {
                count++;
            }
        }
        return count;
    }

    @Override
    public boolean isFinished() {
        return done;
    }

    @Override
    protected void onStop(Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        if (!(other instanceof OwnerGiveItemsTask that) || entries.size() != that.entries.size()) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (!entries.get(i).item().equals(that.entries.get(i).item())
                    || entries.get(i).count() != that.entries.get(i).count()) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected String toDebugString() {
        return "Fetch %d items for owner".formatted(entries.size());
    }
}
