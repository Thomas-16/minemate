package io.github.thomas16.minemate.task;

import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.StorageHelper;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.entity.CompanionEntity;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs an acquisition attempt and re-plans it locally instead of dying with it. A descendant
 * {@code ResourceTask} failure reason is latched as a diagnostic but never acted on while the
 * attempt is still running, and when the attempt stops short of the target, a changed inventory
 * is treated as evidence the pipeline was working: the next attempt re-plans against what is now
 * held, which is exactly the rescue the hosted replan chain performs at the cost of a model
 * round trip per step. An unchanged inventory funds nothing, so a genuinely unavailable
 * resource still fails as soon as its first attempt does.
 */
final class ResilientAcquisitionTask extends Task {
    private static final int MAX_ATTEMPTS = 3;

    private final ItemTarget target;
    private final Supplier<Task> attemptFactory;
    private final String what;

    private Task child;
    private boolean childTicked;
    private int attempts;
    private Map<Item, Integer> attemptBaseline = Map.of();
    private String lastObservedFailure;
    private String terminalFailure;
    private boolean done;

    ResilientAcquisitionTask(ItemTarget target, Supplier<Task> attemptFactory, String what) {
        this.target = target;
        this.attemptFactory = attemptFactory;
        this.what = what;
    }

    Optional<String> terminalFailure() {
        return Optional.ofNullable(terminalFailure);
    }

    int attempts() {
        return attempts;
    }

    @Override
    protected void onStart() {
        child = null;
        childTicked = false;
        attempts = 1;
        attemptBaseline = Map.of();
        lastObservedFailure = null;
        terminalFailure = null;
        done = false;
    }

    @Override
    protected Task onTick() {
        if (StorageHelper.itemTargetsMet(controller, target)) {
            done = true;
            stop();
            return null;
        }
        if (child == null) {
            child = attemptFactory.get();
            if (child == null) {
                finishExhausted();
                return null;
            }
            childTicked = false;
            attemptBaseline = fingerprint();
        }
        latchObservedFailure();
        // Only after the runner has had it: PlayerEngine assigns a task's controller when it
        // first ticks it, and ResourceTask.isFinished dereferences that controller.
        if (childTicked && (child.stopped() || child.isFinished())) {
            if (attempts < MAX_ATTEMPTS && !fingerprint().equals(attemptBaseline)) {
                attempts++;
                MineMate.LOGGER.info("Acquisition attempt for {} ended with progress; re-planning"
                        + " (attempt {}/{})", what, attempts, MAX_ATTEMPTS);
                child = null;
                setDebugState("Retrying acquisition of " + what + " (attempt " + attempts + ")");
                return null;
            }
            finishExhausted();
            return null;
        }
        childTicked = true;
        setDebugState("Acquiring " + what + " (attempt " + attempts + "/" + MAX_ATTEMPTS + ")");
        return child;
    }

    private void finishExhausted() {
        terminalFailure = lastObservedFailure != null
                ? lastObservedFailure : Reasons.RESOURCE_UNAVAILABLE;
        fail(terminalFailure);
    }

    private void latchObservedFailure() {
        String[] reason = new String[1];
        thisOrChildSatisfies(task -> {
            if (task instanceof ResourceTask resourceTask) {
                reason[0] = resourceTask.getAggregatedFailureReason().orElse(null);
            }
            return reason[0] != null;
        });
        if (reason[0] != null) {
            lastObservedFailure = reason[0];
        }
    }

    private Map<Item, Integer> fingerprint() {
        if (!(controller.getEntity() instanceof CompanionEntity companion)) {
            return Map.of();
        }
        Container inventory = companion.getLivingInventory();
        Map<Item, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
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
        return other instanceof ResilientAcquisitionTask acquisition
                && what.equals(acquisition.what)
                && target.getTargetCount() == acquisition.target.getTargetCount();
    }

    @Override
    protected String toDebugString() {
        return "Acquire %s (attempt %d/%d)".formatted(what, attempts, MAX_ATTEMPTS);
    }
}
