package io.github.thomas16.minemate.context;

import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.integration.ReflexKind;
import io.github.thomas16.minemate.integration.ReflexSnapshot;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;

import java.util.Objects;

/**
 * Per-companion world context: the event journal, the replan triggers, and a per-tick memoized
 * {@link WorldSnapshot}.
 *
 * <p>Ticked last in the companion's server-side block so that everything it samples — engine state,
 * reflex telemetry, the supervisor's outcome for this tick, the plan executor's step — has already
 * settled.
 */
public final class CompanionContext {

    private static final int SAMPLE_INTERVAL_TICKS = 20;

    private final CompanionEntity companion;
    private final CompanionJournal journal = new CompanionJournal();
    private final ReplanTriggers triggers;

    private WorldSnapshot cachedSnapshot;
    private long cachedAtGameTime = Long.MIN_VALUE;
    private ReflexKind lastReflexKind = ReflexKind.NONE;
    private boolean ownerWasNear;
    private long observedTicks;

    public CompanionContext(CompanionEntity companion) {
        this.companion = Objects.requireNonNull(companion, "companion");
        this.triggers = new ReplanTriggers(companion);
    }

    public CompanionJournal journal() {
        return journal;
    }

    public ReplanTriggers triggers() {
        return triggers;
    }

    /**
     * The current snapshot, memoized for this game tick.
     *
     * <p>Capture is on demand only. Do not call this every tick to "keep it warm": that spends
     * raycasts and entity queries per companion per tick for a consumer that may never ask.
     */
    public WorldSnapshot snapshot() {
        long now = companion.level().getGameTime();
        if (cachedSnapshot == null || cachedAtGameTime != now) {
            cachedSnapshot = WorldSnapshots.capture(companion);
            cachedAtGameTime = now;
        }
        return cachedSnapshot;
    }

    /** Server-thread-only. Samples journal-worthy world changes, then lets triggers dispatch. */
    public void tick() {
        observedTicks++;
        sampleReflex();
        if (observedTicks % SAMPLE_INTERVAL_TICKS == 0) {
            sampleOwner();
        }
        triggers.tick();
    }

    private void sampleReflex() {
        ReflexSnapshot reflex = companion.getReflexSnapshot();
        if (reflex == null) {
            return;
        }
        ReflexKind kind = reflex.kind();
        if (kind == lastReflexKind) {
            return;
        }
        if (kind == ReflexKind.NONE) {
            record(EventKind.REFLEX_ENDED, lastReflexKind.name().toLowerCase(java.util.Locale.ROOT)
                    + " reflex ended");
        } else {
            String detail = reflex.detail().isBlank() ? "" : " (" + reflex.detail() + ")";
            record(EventKind.REFLEX_STARTED,
                    kind.name().toLowerCase(java.util.Locale.ROOT) + " reflex started" + detail);
        }
        lastReflexKind = kind;
    }

    private void sampleOwner() {
        boolean near = companion.resolveOwnerInLevel().isPresent();
        if (near == ownerWasNear) {
            return;
        }
        ownerWasNear = near;
        record(near ? EventKind.OWNER_ARRIVED : EventKind.OWNER_LEFT,
                near ? "the owner is nearby" : "the owner is no longer reachable");
    }

    /** Called from the companion's damage path, where the attacker is still known. */
    public void onDamaged(DamageSource source, float amount) {
        if (amount <= 0.0F) {
            return;
        }
        String attacker = attackerName(source);
        journal.record(new JournalEntry(companion.level().getGameTime(), EventKind.DAMAGE_TAKEN,
                "took %.0f damage from %s".formatted(amount, attacker), attacker));
        triggers.arm(ReplanReason.DAMAGE_TAKEN, "took damage from " + attacker);
    }

    /**
     * Called from item pickup. The item must be captured before the inventory insert, which mutates
     * the stack and can leave it empty.
     */
    public void onItemPickedUp(Item item, int count) {
        if (item == null || count <= 0) {
            return;
        }
        String name = BuiltInRegistries.ITEM.getKey(item).getPath();
        journal.record(new JournalEntry(companion.level().getGameTime(), EventKind.ITEM_PICKED_UP,
                "picked up " + name + " x" + count, name));
    }

    public void record(EventKind kind, String summary) {
        journal.record(companion.level().getGameTime(), kind, summary);
    }

    public void record(EventKind kind, String summary, String detail) {
        journal.record(new JournalEntry(companion.level().getGameTime(), kind, summary, detail));
    }

    /** Clears remembered state so a headless scenario is not read by the next one. */
    public void reset() {
        journal.clear();
        triggers.reset();
        cachedSnapshot = null;
        cachedAtGameTime = Long.MIN_VALUE;
        lastReflexKind = ReflexKind.NONE;
    }

    private static String attackerName(DamageSource source) {
        Entity attacker = source.getEntity();
        if (attacker != null) {
            return BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).getPath();
        }
        return source.getMsgId();
    }
}
