package io.github.thomas16.minemate.task;

import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.movement.GetToEntityTask;

import io.github.thomas16.minemate.entity.CompanionEntity;

import net.minecraft.server.level.ServerPlayer;

/**
 * Walks to the persisted MineMate owner's current position, rebinding whenever the owner entity
 * changes (respawn, rejoin). The engine child tracks a live entity and never self-finishes;
 * arrival is owned by the goal probe, so this task declares no completion of its own.
 */
class ComeToOwnerTask extends Task {
    private final double arriveDistance;
    /**
     * Slightly tighter than the probe's radius: the engine goal accepts block-centers near the
     * owner's feet while the probe measures feet-to-feet distance, and an engine that parks
     * satisfied just outside the probe's radius would idle until the goal timeout.
     */
    private final double engineDistance;
    private ServerPlayer trackedOwner;
    private GetToEntityTask approach;

    ComeToOwnerTask(double arriveDistance) {
        this.arriveDistance = arriveDistance;
        this.engineDistance = Math.max(0.5, arriveDistance - 0.5);
    }

    @Override
    protected void onStart() {
        trackedOwner = null;
        approach = null;
    }

    @Override
    protected Task onTick() {
        if (!(controller.getEntity() instanceof CompanionEntity companion)) {
            setDebugState("Waiting for owner");
            return null;
        }
        ServerPlayer owner = companion.resolveOwnerInLevel().orElse(null);
        if (owner == null) {
            // The goal probe blocks the goal as owner_offline within one sample; just idle.
            setDebugState("Waiting for owner");
            return null;
        }
        if (owner != trackedOwner) {
            trackedOwner = owner;
            approach = new GetToEntityTask(owner, engineDistance);
        }
        setDebugState("Approaching owner");
        return approach;
    }

    @Override
    protected void onStop(Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ComeToOwnerTask come
                && Math.abs(arriveDistance - come.arriveDistance) < 0.1;
    }

    @Override
    protected String toDebugString() {
        return "Come to owner (within %.1f blocks)".formatted(arriveDistance);
    }
}
