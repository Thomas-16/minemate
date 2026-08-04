package io.github.thomas16.minemate.debug;

import io.github.thomas16.minemate.entity.CompanionEntity;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/** Payload registration and server-side authorization for the live debug screen. */
public final class CompanionDebugNetworking {
    private CompanionDebugNetworking() {
    }

    public static void init() {
        PayloadTypeRegistry.playC2S().register(
                CompanionDebugRequestPayload.TYPE, CompanionDebugRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(
                CompanionDebugSnapshotPayload.TYPE, CompanionDebugSnapshotPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CompanionDebugRequestPayload.TYPE,
                (payload, context) -> refresh(context.player(), payload.companionId()));
    }

    /** Returns false when the connected client does not provide MineMate's debug UI. */
    public static boolean open(ServerPlayer player, CompanionEntity companion) {
        if (!ServerPlayNetworking.canSend(player, CompanionDebugSnapshotPayload.TYPE)) {
            return false;
        }
        ServerPlayNetworking.send(player, CompanionDebugSnapshotPayload.available(true,
                CompanionDebugSnapshot.capture(companion, player)));
        return true;
    }

    private static void refresh(ServerPlayer player, UUID companionId) {
        if (!ServerPlayNetworking.canSend(player, CompanionDebugSnapshotPayload.TYPE)) {
            return;
        }
        Entity entity = player.serverLevel().getEntity(companionId);
        if (!(entity instanceof CompanionEntity companion)
                || !companion.isAlive()
                || companion.isRemoved()
                || companion.getOwnerUuid().filter(player.getUUID()::equals).isEmpty()) {
            ServerPlayNetworking.send(player, CompanionDebugSnapshotPayload.unavailable(
                    companionId, "Companion is no longer available in this dimension"));
            return;
        }
        ServerPlayNetworking.send(player, CompanionDebugSnapshotPayload.available(false,
                CompanionDebugSnapshot.capture(companion, player)));
    }
}
