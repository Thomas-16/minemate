package io.github.thomas16.minemate.tool;

import io.github.thomas16.minemate.entity.CompanionEntity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.Optional;

/**
 * Everything a {@link ToolDescriptor.GoalBuilder} needs that is not an argument.
 *
 * <p>This is what lets one uniform {@code build(args, ctx)} signature cover goal factories whose
 * Java signatures want a companion, a level, a server, or the owner.
 *
 * <p>Build a fresh instance per step rather than caching one per plan: a captured level goes stale
 * across a dimension change.
 */
public record ToolContext(CompanionEntity companion, ServerLevel level, MinecraftServer server) {

    public ToolContext {
        Objects.requireNonNull(companion, "companion");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(server, "server");
    }

    public static ToolContext of(CompanionEntity companion) {
        Objects.requireNonNull(companion, "companion");
        if (!(companion.level() instanceof ServerLevel serverLevel) || companion.getServer() == null) {
            throw new IllegalStateException("Tools can only be bound on the server");
        }
        return new ToolContext(companion, serverLevel, companion.getServer());
    }

    public Optional<ServerPlayer> owner() {
        return companion.resolveOwner();
    }
}
