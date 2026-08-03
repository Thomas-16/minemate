package io.github.thomas16.minemate.integration;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.chains.FoodChain;
import com.player2.playerengine.chains.ForceEquipGearChain;
import com.player2.playerengine.chains.MLGBucketFallChain;
import com.player2.playerengine.chains.MobDefenseChain;
import com.player2.playerengine.chains.PlayerDefenseChain;
import com.player2.playerengine.chains.UnstuckChain;
import com.player2.playerengine.chains.WorldSurvivalChain;
import com.player2.playerengine.tasks.base.TaskChain;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.entity.CompanionEntity;

import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

/** Samples automatic PlayerEngine chain ownership once per server-side entity tick. */
public final class CompanionReflexTelemetry {
    private final CompanionEntity companion;
    private final PlayerEngineController controller;
    private long observedTicks;
    private long transitionTick;
    private ReflexSnapshot snapshot;

    public CompanionReflexTelemetry(CompanionEntity companion, PlayerEngineController controller) {
        this.companion = Objects.requireNonNull(companion, "companion");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.snapshot = new ReflexSnapshot(ReflexKind.NONE, "", 0L,
                controller.getShouldDefendFromHostiles(), -1.0, "");
    }

    public void tick() {
        observedTicks++;
        TaskChain chain = controller.getTaskRunner().getCurrentTaskChain();
        ReflexKind kind = classify(chain);
        String chainName = kind == ReflexKind.NONE || chain == null ? "" : chain.getName();
        double ownerDistance = ownerDistance();
        String detail = detail(chain);

        if (kind != snapshot.kind() || !chainName.equals(snapshot.chain())) {
            MineMate.LOGGER.debug("Companion {} reflex transition {} -> {}{}",
                    companion.getUUID(), snapshot.kind(), kind,
                    chainName.isEmpty() ? "" : " (" + chainName + ")");
            transitionTick = observedTicks;
        }
        snapshot = new ReflexSnapshot(kind, chainName, transitionTick,
                controller.getShouldDefendFromHostiles(), ownerDistance, detail);
    }

    public ReflexSnapshot snapshot() {
        return snapshot;
    }

    private ReflexKind classify(TaskChain chain) {
        if (chain == null || chain == controller.getUserTaskChain()) {
            return ReflexKind.NONE;
        }
        if (chain instanceof MobDefenseChain) {
            return ReflexKind.MOB_DEFENSE;
        }
        if (chain instanceof PlayerDefenseChain) {
            return ReflexKind.PLAYER_DEFENSE;
        }
        if (chain instanceof FoodChain) {
            return ReflexKind.EATING;
        }
        if (chain instanceof MLGBucketFallChain) {
            return ReflexKind.MLG_CLUTCH;
        }
        if (chain instanceof ForceEquipGearChain) {
            return ReflexKind.EQUIPMENT;
        }
        if (chain instanceof UnstuckChain) {
            return ReflexKind.UNSTUCK;
        }
        if (chain instanceof WorldSurvivalChain) {
            return ReflexKind.WORLD_SURVIVAL;
        }
        return ReflexKind.OTHER;
    }

    private String detail(TaskChain chain) {
        if (chain instanceof MobDefenseChain defense) {
            if (defense.isShielding()) {
                return "shielding";
            }
            if (defense.isPuttingOutFire()) {
                return "putting out fire";
            }
            if (defense.isDoingAcrobatics()) {
                return "evading danger";
            }
            return defense.isInDangerNow() ? "responding to danger" : "defending";
        }
        if (chain instanceof FoodChain food) {
            if (food.isStarving()) {
                return "starving; seeking food";
            }
            return food.isTryingToEat() ? "eating" : food.needsToEat() ? "seeking food" : "food response";
        }
        if (chain instanceof MLGBucketFallChain mlg) {
            return mlg.isChorusFruiting() ? "using chorus fruit" : "fall clutch";
        }
        return chain == null ? "" : chain.getName();
    }

    private double ownerDistance() {
        if (companion.getServer() == null) {
            return -1.0;
        }
        ServerPlayer owner = companion.getOwnerUuid()
                .map(uuid -> companion.getServer().getPlayerList().getPlayer(uuid))
                .orElse(null);
        return owner == null || owner.level() != companion.level() ? -1.0 : companion.distanceTo(owner);
    }
}
