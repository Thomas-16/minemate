package io.github.thomas16.minemate.context;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.entity.CompanionEntity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Decides when the companion should reconsider its plan, and tells listeners at most rarely.
 *
 * <p>The companion ticks at 20 Hz, and every source of a trigger is sampled inside that loop, so
 * the whole design is about <em>not</em> firing. Five independent brakes apply:
 *
 * <ol>
 *   <li>{@link #arm} never dispatches; it records a pending reason, so repeated arms within a tick
 *       collapse into one.</li>
 *   <li>{@link #tick} fires at most one trigger per tick, highest priority first.</li>
 *   <li>Each reason has its own cooldown before it may fire again.</li>
 *   <li>A global cooldown gates every reason, capping the rate at 12 per minute.</li>
 *   <li>Polled reasons are only evaluated every {@value #SAMPLE_INTERVAL_TICKS} ticks.</li>
 * </ol>
 *
 * <p>Stage 4 has no planner consumer; it exists so stage 5 subscribes with one line, and so the
 * headless suite can assert the firing rate now rather than discovering it against a paid API.
 */
public final class ReplanTriggers {

    /** Receives a fired trigger. Stateless: the companion arrives with the event. */
    public interface Listener {
        void onReplanTrigger(CompanionEntity companion, ReplanTrigger trigger);
    }

    private static final int GLOBAL_COOLDOWN_TICKS = 100;
    private static final int SAMPLE_INTERVAL_TICKS = 20;
    private static final double OWNER_MOVE_THRESHOLD = 100.0;
    private static final int IDLE_TICKS = 1200;
    private static final long NIGHT_START = 13_000L;

    private static final Map<ReplanReason, Integer> COOLDOWNS = new EnumMap<>(Map.of(
            ReplanReason.DAMAGE_TAKEN, 200,
            ReplanReason.TASK_OUTCOME, 20,
            ReplanReason.PLAN_FINISHED, 20,
            ReplanReason.OWNER_LOST, 600,
            ReplanReason.NIGHTFALL, 12_000,
            ReplanReason.OWNER_MOVED_FAR, 600,
            ReplanReason.IDLE, 1200));

    /**
     * "Nothing has fired yet." Not {@code Long.MIN_VALUE}: the cooldown check subtracts this from
     * {@link #observedTicks}, and that subtraction would overflow to a negative difference, so the
     * cooldown would appear permanently unexpired and no trigger could ever fire.
     */
    private static final long NEVER_FIRED = -1L;

    private static final List<Listener> GLOBAL = new CopyOnWriteArrayList<>();

    private final CompanionEntity companion;
    private final List<Listener> listeners = new ArrayList<>(GLOBAL);
    private final Map<ReplanReason, String> pending = new EnumMap<>(ReplanReason.class);
    private final Map<ReplanReason, Long> lastFired = new EnumMap<>(ReplanReason.class);

    private long observedTicks;
    private long lastAnyFireTick = NEVER_FIRED;
    private long lastDayTime;
    private double ownerAnchorX;
    private double ownerAnchorZ;
    private boolean ownerAnchored;
    private boolean ownerWasPresent;
    private int idleTicks;
    private ReplanTrigger lastTrigger;
    private int firedCount;

    public ReplanTriggers(CompanionEntity companion) {
        this.companion = Objects.requireNonNull(companion, "companion");
    }

    /** Stage 5 registers here once; every companion created afterwards picks it up. */
    public static void registerGlobal(Listener listener) {
        GLOBAL.add(Objects.requireNonNull(listener, "listener"));
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Records that something happened. Never dispatches; {@link #tick} is the only dispatcher. */
    public void arm(ReplanReason reason, String detail) {
        pending.put(reason, detail == null ? "" : detail);
    }

    /** Server-thread-only. Polls world-derived reasons, then fires at most one trigger. */
    public void tick() {
        observedTicks++;
        if (observedTicks % SAMPLE_INTERVAL_TICKS == 0) {
            poll();
        }
        if (pending.isEmpty()) {
            return;
        }
        if (lastAnyFireTick != NEVER_FIRED && observedTicks - lastAnyFireTick < GLOBAL_COOLDOWN_TICKS) {
            return;
        }
        for (ReplanReason reason : ReplanReason.values()) {
            String detail = pending.get(reason);
            if (detail == null) {
                continue;
            }
            Long previous = lastFired.get(reason);
            if (previous != null && observedTicks - previous < COOLDOWNS.getOrDefault(reason, 100)) {
                // Still cooling down. Leave it pending so it can fire once the window opens.
                continue;
            }
            pending.remove(reason);
            fire(new ReplanTrigger(reason, companion.level().getGameTime(), detail));
            return;
        }
    }

    private void poll() {
        long dayTime = companion.level().getDayTime() % 24_000L;
        if (lastDayTime < NIGHT_START && dayTime >= NIGHT_START && dayTime < 23_000L) {
            arm(ReplanReason.NIGHTFALL, "night is falling");
        }
        lastDayTime = dayTime;

        companion.resolveOwnerInLevel().ifPresentOrElse(owner -> {
            if (!ownerWasPresent) {
                ownerWasPresent = true;
                anchorOwner(owner.getX(), owner.getZ());
            }
            if (!ownerAnchored) {
                anchorOwner(owner.getX(), owner.getZ());
            }
            double dx = owner.getX() - ownerAnchorX;
            double dz = owner.getZ() - ownerAnchorZ;
            if (Math.sqrt(dx * dx + dz * dz) >= OWNER_MOVE_THRESHOLD) {
                anchorOwner(owner.getX(), owner.getZ());
                arm(ReplanReason.OWNER_MOVED_FAR, "the owner travelled a long way");
            }
        }, () -> {
            if (ownerWasPresent) {
                ownerWasPresent = false;
                ownerAnchored = false;
                arm(ReplanReason.OWNER_LOST, "the owner is not reachable");
            }
        });

        boolean busy = companion.getSupervisor() != null && companion.getSupervisor().isBusy();
        idleTicks = busy ? 0 : idleTicks + SAMPLE_INTERVAL_TICKS;
        if (idleTicks >= IDLE_TICKS) {
            idleTicks = 0;
            arm(ReplanReason.IDLE, "nothing to do");
        }
    }

    private void anchorOwner(double x, double z) {
        ownerAnchorX = x;
        ownerAnchorZ = z;
        ownerAnchored = true;
    }

    private void fire(ReplanTrigger trigger) {
        lastFired.put(trigger.reason(), observedTicks);
        lastAnyFireTick = observedTicks;
        lastTrigger = trigger;
        firedCount++;
        for (Listener listener : listeners) {
            try {
                listener.onReplanTrigger(companion, trigger);
            } catch (Throwable error) {
                MineMate.LOGGER.error("Replan listener failed for {}", trigger.reason(), error);
            }
        }
    }

    public Optional<ReplanTrigger> lastTrigger() {
        return Optional.ofNullable(lastTrigger);
    }

    /** How many triggers have fired for this companion; the headless suite asserts on the rate. */
    public int firedCount() {
        return firedCount;
    }

    /** Drops pending and historical state so a headless scenario starts from a clean slate. */
    public void reset() {
        pending.clear();
        lastFired.clear();
        lastAnyFireTick = NEVER_FIRED;
        lastTrigger = null;
        firedCount = 0;
        idleTicks = 0;
        ownerAnchored = false;
    }
}
