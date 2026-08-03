package io.github.thomas16.minemate.integration;

import com.player2.playerengine.FollowMode;
import com.player2.playerengine.PlayerEngineController;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.entity.CompanionEntity;

import net.minecraft.server.level.ServerPlayer;

/**
 * Keeps PlayerEngine's own mob defense on a leash to the companion's owner.
 *
 * <p>PlayerEngine arbitrates chains purely by priority, and {@code UserTaskChain}
 * — where every MineMate goal runs — reports a flat 50. {@code MobDefenseChain}
 * reports 65 while chasing "annoying hostiles" and 70/80 while running
 * {@code RunAwayFromHostilesTask(30.0)}, so it outranks us and drives the body
 * itself. Neither of those tasks knows the companion has an owner, which is how
 * a companion ends up sprinting to the horizon and never coming back.
 *
 * <p>The engine's lever is inverted relative to its name:
 * {@code MobDefenseChain.getPriority()} starts with
 * {@code if (controller.getShouldDefendFromHostiles()) return 0.0f;} — setting
 * the flag <em>suppresses</em> the chain. {@code setFollowMode} is its only
 * other writer ({@code shouldDefendFromHostiles = (mode == COWARD)}), so we
 * rewrite the flag every tick; a later mode change cannot strand it.
 */
public final class CompanionDefensePolicy {

    /** Beyond this distance from the owner, mob defense loses the body. */
    private static final double LEASH_TRIP = 24.0;

    /** Defense is handed back only well inside the leash; one threshold flaps at 20 Hz. */
    private static final double LEASH_REARM = 20.0;

    private CompanionDefensePolicy() {
    }

    /**
     * Called once per controller. DEFENDER leaves engine defense enabled and
     * turns on {@code FollowPlayerTask}'s own 16-block break-off while a follow
     * task is ticking; the leash below is the backstop that also covers goto,
     * mine, fetch and idle, where that break-off never runs.
     */
    public static void install(PlayerEngineController controller) {
        controller.setFollowMode(FollowMode.DEFENDER);
    }

    /** Server-thread-only. Re-decides whether mob defense may own the body this tick. */
    public static void tick(CompanionEntity companion, PlayerEngineController controller) {
        boolean suppressed = controller.getShouldDefendFromHostiles();
        ServerPlayer owner = owner(companion);
        double distance = owner == null || owner.level() != companion.level()
                ? -1.0 : companion.distanceTo(owner);

        boolean decision = distance < 0.0                      // nothing to leash against
                || distance >= LEASH_TRIP                      // too far to let it wander further
                || (suppressed && distance > LEASH_REARM);     // not yet back well inside the leash
        controller.setShouldDefendFromHostiles(decision);

        if (decision != suppressed) {
            MineMate.LOGGER.debug("Companion {} {} PlayerEngine mob defense (owner distance {})",
                    companion.getUUID(), decision ? "suppressed" : "restored",
                    distance < 0.0 ? "unknown" : "%.1f".formatted(distance));
        }
    }

    private static ServerPlayer owner(CompanionEntity companion) {
        if (companion.getServer() == null) {
            return null;
        }
        return companion.getOwnerUuid()
                .map(uuid -> companion.getServer().getPlayerList().getPlayer(uuid))
                .orElse(null);
    }
}
