package io.github.thomas16.minemate.debug;

import io.github.thomas16.minemate.MineMate;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Client request for the next live snapshot of an already-open debug screen. */
public record CompanionDebugRequestPayload(UUID companionId) implements CustomPacketPayload {
    public static final Type<CompanionDebugRequestPayload> TYPE =
            new Type<>(MineMate.id("companion_debug_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CompanionDebugRequestPayload> CODEC =
            StreamCodec.of((buffer, payload) -> buffer.writeUUID(payload.companionId()),
                    buffer -> new CompanionDebugRequestPayload(buffer.readUUID()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
