package io.github.thomas16.minemate.context;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded conversational memory: what the player said, what the companion said, and concise
 * game-authored records of shared activity, in order.
 *
 * <p>Eviction removes complete oldest <em>turn groups</em> -- the activity that preceded an owner
 * utterance, that utterance, and everything up to the next owner utterance -- so a wire transcript
 * built from this log can never begin with an orphan assistant reply, and an owner/companion pair
 * is never split merely to satisfy the character budget. The newest group is never evicted: the
 * conversation currently happening is not sacrificed to a byte count.
 *
 * <p>The log is serialized into the companion's entity NBT, so conversation survives ordinary
 * entity unload/load and server save/restart. Active plans are intentionally non-persistent;
 * losing current work does not require also giving the companion amnesia.
 */
public final class ConversationLog {

    /** Per-entry char-budget overhead so many tiny entries cannot dodge the byte bound. */
    private static final int ENTRY_OVERHEAD_CHARS = 16;

    public enum EntryKind {
        OWNER_UTTERANCE,
        COMPANION_UTTERANCE,
        /** A trusted game observation, never rendered as anyone's speech. */
        ACTIVITY
    }

    public record Entry(EntryKind kind, String text, long gameTime) {
        public Entry {
            Objects.requireNonNull(kind, "kind");
            text = text == null ? "" : text;
        }
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final int maxTurns;
    private final int maxChars;

    public ConversationLog(int maxTurns, int maxChars) {
        this.maxTurns = Math.max(1, maxTurns);
        this.maxChars = Math.max(ENTRY_OVERHEAD_CHARS, maxChars);
    }

    public void recordOwner(String text, long gameTime) {
        record(new Entry(EntryKind.OWNER_UTTERANCE, text, gameTime));
    }

    public void recordCompanion(String text, long gameTime) {
        record(new Entry(EntryKind.COMPANION_UTTERANCE, text, gameTime));
    }

    public void recordActivity(String summary, long gameTime) {
        record(new Entry(EntryKind.ACTIVITY, summary, gameTime));
    }

    private void record(Entry entry) {
        if (entry.text().isBlank()) {
            return;
        }
        entries.addLast(entry);
        evict();
    }

    /** Immutable snapshot, oldest first: the exact material a wire transcript is rendered from. */
    public List<Entry> snapshot() {
        return List.copyOf(entries);
    }

    public Entry lastEntry() {
        return entries.peekLast();
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }

    private void evict() {
        while ((turnCount() > maxTurns || charSize() > maxChars) && evictOldestGroup()) {
            // Keep going until inside both bounds or only the newest group remains.
        }
    }

    private int turnCount() {
        int turns = 0;
        for (Entry entry : entries) {
            if (entry.kind() == EntryKind.OWNER_UTTERANCE) {
                turns++;
            }
        }
        return turns;
    }

    private int charSize() {
        int chars = 0;
        for (Entry entry : entries) {
            chars += entry.text().length() + ENTRY_OVERHEAD_CHARS;
        }
        return chars;
    }

    /**
     * Drops the oldest turn group: everything from the head up to, but excluding, the second owner
     * utterance. Returns false when no complete group can go -- the log holds at most one owner
     * turn -- so a caller's eviction loop always terminates.
     */
    private boolean evictOldestGroup() {
        int secondOwnerIndex = -1;
        int ownerSeen = 0;
        int index = 0;
        for (Entry entry : entries) {
            if (entry.kind() == EntryKind.OWNER_UTTERANCE) {
                ownerSeen++;
                if (ownerSeen == 2) {
                    secondOwnerIndex = index;
                    break;
                }
            }
            index++;
        }
        if (secondOwnerIndex < 0) {
            return false;
        }
        for (int removed = 0; removed < secondOwnerIndex; removed++) {
            entries.removeFirst();
        }
        return true;
    }

    // ---- NBT ---------------------------------------------------------------------------------

    public ListTag writeNbt() {
        ListTag list = new ListTag();
        for (Entry entry : entries) {
            CompoundTag tag = new CompoundTag();
            tag.putString("kind", entry.kind().name());
            tag.putString("text", entry.text());
            tag.putLong("time", entry.gameTime());
            list.add(tag);
        }
        return list;
    }

    /** Replaces the log with saved entries, skipping unknown kinds and re-enforcing bounds. */
    public void readNbt(ListTag list) {
        entries.clear();
        if (list == null) {
            return;
        }
        List<Entry> loaded = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            if (list.get(index) instanceof CompoundTag tag) {
                EntryKind kind;
                try {
                    kind = EntryKind.valueOf(tag.getString("kind"));
                } catch (IllegalArgumentException unknownKind) {
                    // A future version's entry kind: drop the entry, keep the conversation.
                    continue;
                }
                String text = tag.getString("text");
                if (!text.isBlank()) {
                    loaded.add(new Entry(kind, text, tag.getLong("time")));
                }
            }
        }
        entries.addAll(loaded);
        evict();
    }

    /** NBT list element type for {@link net.minecraft.nbt.CompoundTag#getList}. */
    public static int nbtListType() {
        return Tag.TAG_COMPOUND;
    }
}
