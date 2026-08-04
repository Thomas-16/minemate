package io.github.thomas16.minemate.mixin;

import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.cooking.SmeltDeferredTask;
import com.player2.playerengine.tasks.movement.GetCloseToBlockTask;

import io.github.thomas16.minemate.integration.CompanionReachability;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Requires a real block reach before PlayerEngine loads or collects from a furnace. */
@Mixin(value = SmeltDeferredTask.class, remap = false)
public abstract class SmeltDeferredTaskMixin extends Task {

    @Shadow
    private BlockPos furnacePos;

    @Redirect(
            method = "isAtFurnace",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/BlockPos;closerThan("
                            + "Lnet/minecraft/core/Vec3i;D)Z",
                    remap = true),
            require = 1)
    private boolean minemate$requireReachAtFurnace(BlockPos target, Vec3i entityPos,
            double distance) {
        return target.closerThan(entityPos, distance)
                && CompanionReachability.canReachBlock(controller, target);
    }

    @Inject(method = "tickLoad", at = @At("HEAD"), cancellable = true, require = 1)
    private void minemate$requireReachBeforeLoad(CallbackInfoReturnable<Task> callback) {
        if (furnacePos != null && !CompanionReachability.canReachBlock(controller, furnacePos)) {
            callback.setReturnValue(new GetCloseToBlockTask(furnacePos));
        }
    }
}
