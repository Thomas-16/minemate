package io.github.thomas16.minemate.planner;

import io.github.thomas16.minemate.task.Goals;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The evidence side of an {@link OrderContract}: per deliverable, how many units are confirmed in
 * the owner's inventory and what the last deterministic step observed. Every number here is
 * measured — delivered units come from owner-inventory deltas latched by fetch probes or observed
 * against the baseline captured at contract time, never from a plan step claiming success.
 *
 * <p>Delivered credit is monotone: an owner consuming a delivered item (bucket to water bucket)
 * never revokes it. Server thread only, like the {@link StandingOrder} that carries it.
 */
public final class OrderLedger {

    /** Best observed state of one deliverable, derived strictly from evidence. */
    public enum EntryState { PENDING, ACQUIRED, CRAFTED, DELIVERED, BLOCKED }

    public static final class Entry {
        private final String item;
        private final int wanted;
        private int receiptUnits;
        private int ownerObserved;
        private int ownerBaseline = -1;
        private boolean acquired;
        private boolean crafted;
        private String blockedReason;

        private Entry(String item, int wanted) {
            this.item = item;
            this.wanted = wanted;
        }

        public String item() {
            return item;
        }

        public int wanted() {
            return wanted;
        }

        /** Units confirmed in the owner's hands, by receipt or by baseline delta. */
        public int delivered() {
            return Math.max(receiptUnits, ownerObserved);
        }

        public EntryState state() {
            if (delivered() >= wanted) {
                return EntryState.DELIVERED;
            }
            if (blockedReason != null) {
                return EntryState.BLOCKED;
            }
            if (crafted) {
                return EntryState.CRAFTED;
            }
            if (acquired) {
                return EntryState.ACQUIRED;
            }
            return EntryState.PENDING;
        }

        private String describe() {
            String text = item + " " + Math.min(delivered(), wanted) + "/" + wanted + " "
                    + state().name().toLowerCase(java.util.Locale.ROOT);
            return blockedReason != null && state() == EntryState.BLOCKED
                    ? text + " (" + blockedReason + ")"
                    : text;
        }
    }

    private final List<Entry> entries;

    private OrderLedger(List<Entry> entries) {
        this.entries = entries;
    }

    /**
     * One entry per normalized item, duplicates summed — mirroring how {@code Goals.fetchItems}
     * merges entries, so the ledger and the executing goal agree on totals.
     */
    public static OrderLedger seed(OrderContract contract) {
        LinkedHashMap<String, Integer> merged = new LinkedHashMap<>();
        if (contract != null) {
            for (OrderContract.Deliverable deliverable : contract.deliverables()) {
                merged.merge(deliverable.item(), deliverable.count(), Integer::sum);
            }
        }
        List<Entry> entries = new ArrayList<>(merged.size());
        for (Map.Entry<String, Integer> item : merged.entrySet()) {
            entries.add(new Entry(item.getKey(), item.getValue()));
        }
        return new OrderLedger(entries);
    }

    /**
     * Records what the owner already holds, so later owner-inventory deltas can credit deliveries
     * the probes missed (items picked up after their goal ended). An unknown catalogue name leaves
     * the baseline unknown and that entry relies on probe receipts alone.
     */
    public void captureOwnerBaselines(ServerPlayer owner) {
        for (Entry entry : entries) {
            if (entry.ownerBaseline < 0) {
                entry.ownerBaseline = Goals.ownerCountOf(owner, entry.item);
            }
        }
    }

    /** Adds a probe-measured receipt. Items the contract never declared are ignored. */
    public void recordDelivered(String item, int units) {
        Entry entry = entryOf(item);
        if (entry == null || units <= 0) {
            return;
        }
        entry.receiptUnits += units;
        entry.blockedReason = null;
    }

    public void recordCrafted(String item) {
        Entry entry = entryOf(item);
        if (entry != null) {
            entry.crafted = true;
            entry.blockedReason = null;
        }
    }

    public void recordAcquired(String item) {
        Entry entry = entryOf(item);
        if (entry != null) {
            entry.acquired = true;
            entry.blockedReason = null;
        }
    }

    /** Marks a shortfall; an entry whose delivery already completed keeps its credit. */
    public void recordBlocked(String item, String reason) {
        Entry entry = entryOf(item);
        if (entry != null && entry.delivered() < entry.wanted) {
            entry.blockedReason = reason == null || reason.isBlank() ? "blocked" : reason;
        }
    }

    /**
     * Tops delivered credit up from the owner's live inventory against the captured baseline.
     * Closes the throw-to-pickup lag window: items in the air when a goal ended are credited once
     * the owner actually holds them.
     */
    public void refreshFromOwner(ServerPlayer owner) {
        for (Entry entry : entries) {
            if (entry.ownerBaseline < 0) {
                continue;
            }
            int current = Goals.ownerCountOf(owner, entry.item);
            if (current >= 0) {
                entry.ownerObserved = Math.max(entry.ownerObserved, current - entry.ownerBaseline);
            }
        }
    }

    /** Whether every deliverable reached the owner. Vacuously true for an empty contract. */
    public boolean deliveryComplete() {
        for (Entry entry : entries) {
            if (entry.delivered() < entry.wanted) {
                return false;
            }
        }
        return true;
    }

    /** Null when complete; otherwise the undelivered remainders, e.g. "0 of 2 oak_planks delivered". */
    public String undeliveredSummary() {
        List<String> missing = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.delivered() < entry.wanted) {
                missing.add(entry.delivered() + " of " + entry.wanted + " " + entry.item
                        + " delivered");
            }
        }
        return missing.isEmpty() ? null : String.join("; ", missing);
    }

    /** One short clause per deliverable, for the planner's LEDGER line. Empty when no entries. */
    public String describe() {
        List<String> parts = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            parts.add(entry.describe());
        }
        return String.join("; ", parts);
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    private Entry entryOf(String item) {
        String normalized = OrderContract.normalize(item);
        for (Entry entry : entries) {
            if (entry.item.equals(normalized)) {
                return entry;
            }
        }
        return null;
    }
}
