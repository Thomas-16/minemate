package io.github.thomas16.minemate.mixin;

import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.entity.GiveItemToPlayerTask;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Gives PlayerEngine's item handoff an actual throw toward its recipient. */
@Mixin(value = GiveItemToPlayerTask.class, remap = false)
public abstract class GiveItemToPlayerTaskMixin extends Task {
    private static final double MIN_HORIZONTAL_SPEED = 0.10;
    private static final double MAX_HORIZONTAL_SPEED = 0.40;
    private static final double VERTICAL_SPEED = 0.20;
    private static final double MIN_DIRECTION_LENGTH_SQUARED = 1.0e-6;

    @Shadow
    @Final
    private String playerName;

    @Redirect(
            method = "throwFromSlot",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;spawnAtLocation("
                            + "Lnet/minecraft/world/item/ItemStack;F)"
                            + "Lnet/minecraft/world/entity/item/ItemEntity;",
                    remap = true),
            require = 1)
    private ItemEntity minemate$throwTowardPlayer(LivingEntity giver, ItemStack stack, float yOffset) {
        ItemEntity dropped = giver.spawnAtLocation(stack, yOffset);
        if (dropped == null) {
            return null;
        }

        Player recipient = controller.getEntityTracker().getPlayerEntity(playerName).orElse(null);
        Vec3 horizontal = recipient == null
                ? Vec3.ZERO
                : new Vec3(recipient.getX() - dropped.getX(), 0.0, recipient.getZ() - dropped.getZ());
        double distance = horizontal.length();

        if (horizontal.lengthSqr() < MIN_DIRECTION_LENGTH_SQUARED) {
            Vec3 look = giver.getLookAngle();
            horizontal = new Vec3(look.x, 0.0, look.z);
        }
        if (horizontal.lengthSqr() < MIN_DIRECTION_LENGTH_SQUARED) {
            return dropped;
        }

        double speed = Mth.clamp(distance / 10.0, MIN_HORIZONTAL_SPEED, MAX_HORIZONTAL_SPEED);
        Vec3 velocity = horizontal.normalize().scale(speed).add(0.0, VERTICAL_SPEED, 0.0);
        dropped.setDeltaMovement(velocity);
        return dropped;
    }
}
