package io.github.thomas16.minemate.debug;

import io.github.thomas16.minemate.MineMate;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Objects;
import java.util.UUID;

/** Opens or refreshes the client screen, or marks its prior snapshot unavailable. */
public record CompanionDebugSnapshotPayload(boolean openScreen, UUID companionId,
        boolean available, String message, CompanionDebugSnapshot snapshot)
        implements CustomPacketPayload {

    private static final int MAX_MESSAGE = 512;

    public static final Type<CompanionDebugSnapshotPayload> TYPE =
            new Type<>(MineMate.id("companion_debug_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CompanionDebugSnapshotPayload> CODEC =
            StreamCodec.of((buffer, payload) -> payload.write(buffer),
                    CompanionDebugSnapshotPayload::read);

    public CompanionDebugSnapshotPayload {
        Objects.requireNonNull(companionId, "companionId");
        message = message == null ? "" : message;
        if (available && snapshot == null) {
            throw new IllegalArgumentException("An available debug payload requires a snapshot");
        }
        if (snapshot != null && !companionId.equals(snapshot.companionId())) {
            throw new IllegalArgumentException("Payload and snapshot companion IDs differ");
        }
    }

    public static CompanionDebugSnapshotPayload available(boolean openScreen,
            CompanionDebugSnapshot snapshot) {
        return new CompanionDebugSnapshotPayload(openScreen, snapshot.companionId(), true, "", snapshot);
    }

    public static CompanionDebugSnapshotPayload unavailable(UUID companionId, String message) {
        return new CompanionDebugSnapshotPayload(false, companionId, false, message, null);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(openScreen);
        buffer.writeUUID(companionId);
        buffer.writeBoolean(available);
        buffer.writeUtf(message, MAX_MESSAGE);
        if (available) {
            snapshot.write(buffer);
        }
    }

    private static CompanionDebugSnapshotPayload read(RegistryFriendlyByteBuf buffer) {
        boolean openScreen = buffer.readBoolean();
        UUID companionId = buffer.readUUID();
        boolean available = buffer.readBoolean();
        String message = buffer.readUtf(MAX_MESSAGE);
        CompanionDebugSnapshot snapshot = available ? CompanionDebugSnapshot.read(buffer) : null;
        return new CompanionDebugSnapshotPayload(openScreen, companionId, available, message, snapshot);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
