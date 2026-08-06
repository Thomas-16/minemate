package io.github.thomas16.minemate.task;

import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.crafting.CraftMacroTasks;
import com.player2.playerengine.tasks.entity.GiveItemToPlayerTask;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.StorageHelper;

import io.github.thomas16.minemate.entity.CompanionEntity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.Optional;

/**
 * Acquires a resource if needed, then delivers it to the persisted MineMate owner. Resolves the
 * owner only after PlayerEngine assigns the task. Delivery is confirmed from the owner's inventory
 * against a baseline captured at start, never from having thrown the items.
 */
class OwnerGiveItemTask extends Task {
    private final String resource;
    private final int count;
    private final Item[] matches;
    private ItemTarget target;
    private Task acquisition;
    private GiveItemToPlayerTask delivery;
    private String ownerName;
    private String acquisitionFailure;
    private String terminalFailure;
    private int ownerBaseline;
    private boolean acquisitionComplete;
    private boolean acquisitionTicked;
    private boolean done;

    OwnerGiveItemTask(String resource, int count, Item[] matches) {
        this.resource = resource;
        this.count = count;
        this.matches = matches.clone();
    }

    @Override
    protected void onStart() {
        done = false;
        acquisitionComplete = false;
        acquisitionTicked = false;
        acquisitionFailure = null;
        terminalFailure = null;
        acquisition = null;
        delivery = null;
        target = null;
        ownerName = null;
        if (!(controller.getEntity() instanceof CompanionEntity companion)) {
            failWith(Reasons.OWNER_OFFLINE);
            return;
        }
        ServerPlayer owner = Goals.owner(companion);
        if (owner == null) {
            failWith(Reasons.OWNER_OFFLINE);
            return;
        }
        ownerName = owner.getGameProfile().getName();
        ownerBaseline = Goals.playerInventoryCount(owner, matches);
        target = new ItemTarget(resource, count);
        acquisitionComplete = StorageHelper.itemTargetsMet(controller, target);
        if (!acquisitionComplete) {
            acquisition = createAcquisitionTask();
        }
    }

    @Override
    protected Task onTick() {
        if (target == null) {
            return null;
        }
        if (!acquisitionComplete) {
            if (StorageHelper.itemTargetsMet(controller, target)) {
                acquisitionComplete = true;
            } else {
                if (acquisition == null) {
                    acquisition = createAcquisitionTask();
                    acquisitionTicked = false;
                }
                // Only after the runner has had it. PlayerEngine assigns a task's controller
                // when it first ticks it, and ResourceTask.isFinished dereferences that
                // controller, so asking a freshly created task whether it is finished throws
                // and takes the server down with it. It never showed up for oak_boat because
                // that resolves to a craft macro, whose isFinished touches no controller.
                if (acquisitionTicked && (acquisition.stopped() || acquisition.isFinished())) {
                    // The wrapper only stops short of the target once its local re-plan
                    // attempts are exhausted, so failing here is legitimate.
                    acquisitionFailure = acquisition instanceof ResilientAcquisitionTask resilient
                            ? resilient.terminalFailure().orElse(Reasons.RESOURCE_UNAVAILABLE)
                            : Reasons.RESOURCE_UNAVAILABLE;
                    failWith(acquisitionFailure);
                    return null;
                }
                acquisitionTicked = true;
                setDebugState("Acquiring " + resource + " for owner");
                return acquisition;
            }
        }
        if (delivery == null) {
            delivery = new GiveItemToPlayerTask(ownerName, target);
        }
        if (ownerReceivedItems()) {
            done = true;
            stop();
            return null;
        }
        if (delivery.stopped() || delivery.isFinished()) {
            setDebugState("Waiting for owner to pick up " + resource);
            return null;
        }
        setDebugState("Delivering " + resource + " to owner");
        return delivery;
    }

    private boolean ownerReceivedItems() {
        if (!(controller.getEntity() instanceof CompanionEntity companion)) {
            return false;
        }
        ServerPlayer owner = Goals.owner(companion);
        return owner != null && Goals.playerInventoryCount(owner, matches) >= ownerBaseline + count;
    }

    private Task createAcquisitionTask() {
        return new ResilientAcquisitionTask(target, () -> {
            CraftMacroTasks.MacroProbe macro = CraftMacroTasks.macroOutcome(controller, target);
            if (macro.outcome() == CraftMacroTasks.MacroOutcome.MACRO_CREATED
                    && macro.taskOrNull() != null) {
                return macro.taskOrNull();
            }
            return TaskCatalogue.getItemTask(resource, count);
        }, resource);
    }

    /** Every failure funnels through here so {@link #failureReason()} always has the reason. */
    private void failWith(String reason) {
        terminalFailure = reason;
        fail(reason);
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
        return other instanceof OwnerGiveItemTask fetch
                && resource.equals(fetch.resource) && count == fetch.count;
    }

    @Override
    protected String toDebugString() {
        return "Fetch %d %s for owner".formatted(count, resource);
    }

    Optional<String> resourceFailure() {
        // Only the latched terminal failure. A live scan of the sub-chain here turned any
        // transient descendant failure reason into an instant BLOCKED for a working pipeline;
        // ResilientAcquisitionTask now owns tree observation and retries.
        return Optional.ofNullable(acquisitionFailure);
    }

    /**
     * The reason this task called {@code fail}, whichever path failed it — unlike
     * {@link #resourceFailure()}, this includes the owner-offline starts, so a sequencing parent
     * can tell a skippable acquisition exhaustion from an unskippable missing owner.
     */
    Optional<String> failureReason() {
        return Optional.ofNullable(terminalFailure);
    }
}
