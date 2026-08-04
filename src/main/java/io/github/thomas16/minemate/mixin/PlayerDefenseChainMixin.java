package io.github.thomas16.minemate.mixin;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.chains.PlayerDefenseChain;

import io.github.thomas16.minemate.entity.CompanionEntity;

import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents PlayerEngine's player-defense reflex from retaliating against a companion's owner. */
@Mixin(value = PlayerDefenseChain.class, remap = false)
public abstract class PlayerDefenseChainMixin {
    @Shadow
    private PlayerEngineController mod;

    /**
     * PlayerEngine 1.21.1-1.4.0 records player attackers by name here and later
     * turns them into {@code KillPlayerTask}s. MineMate deliberately does not set
     * PlayerEngine's own owner field, so filter against the companion's UUID instead.
     */
    @Inject(method = "onPlayerDamage", at = @At("HEAD"), cancellable = true, require = 1)
    private void minemate$ignoreOwnerDamage(Entity attacker, CallbackInfo callback) {
        if (!(this.mod.getPlayer() instanceof CompanionEntity companion) || attacker == null) {
            return;
        }
        if (companion.getOwnerUuid().filter(attacker.getUUID()::equals).isPresent()) {
            callback.cancel();
        }
    }
}
