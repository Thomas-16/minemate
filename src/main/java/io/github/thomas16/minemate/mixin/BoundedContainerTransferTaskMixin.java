package io.github.thomas16.minemate.mixin;

import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.container.BoundedContainerTransferTask;
import com.player2.playerengine.tasks.movement.GetToBlockTask;

import io.github.thomas16.minemate.integration.CompanionReachability;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stops PlayerEngine's direct container transactions from bypassing intervening blocks. */
@Mixin(value = BoundedContainerTransferTask.class, remap = false)
public abstract class BoundedContainerTransferTaskMixin extends Task {

    @Shadow
    @Final
    private BlockPos targetPos;

    @Redirect(
            method = "onTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/BlockPos;closerThan("
                            + "Lnet/minecraft/core/Vec3i;D)Z",
                    remap = true),
            require = 1)
    private boolean minemate$requireReachForArrival(BlockPos target, Vec3i entityPos,
            double distance) {
        return target.closerThan(entityPos, distance)
                && CompanionReachability.canReachBlock(controller, target);
    }

    @Inject(
            method = "onTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/player2/playerengine/containeraccess/ContainerResolver;resolve("
                            + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)"
                            + "Lcom/player2/playerengine/containeraccess/ContainerResolver$Resolution;"),
            cancellable = true,
            require = 1)
    private void minemate$requireReachBeforeOpen(CallbackInfoReturnable<Task> callback) {
        continueNavigatingIfBlocked(callback);
    }

    @Inject(
            method = "onTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/player2/playerengine/containeraccess/ResolvedContainer;container()"
                            + "Lnet/minecraft/world/Container;",
                    ordinal = 0),
            cancellable = true,
            require = 1)
    private void minemate$requireReachBeforeValidation(CallbackInfoReturnable<Task> callback) {
        continueNavigatingIfBlocked(callback);
    }

    @Inject(
            method = "onTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/player2/playerengine/containeraccess/ResolvedContainer;container()"
                            + "Lnet/minecraft/world/Container;",
                    ordinal = 1),
            cancellable = true,
            require = 1)
    private void minemate$requireReachBeforeTransfer(CallbackInfoReturnable<Task> callback) {
        continueNavigatingIfBlocked(callback);
    }

    private void continueNavigatingIfBlocked(CallbackInfoReturnable<Task> callback) {
        if (!CompanionReachability.canReachBlock(controller, targetPos)) {
            callback.setReturnValue(new GetToBlockTask(targetPos));
        }
    }
}
