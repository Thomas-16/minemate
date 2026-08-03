package io.github.thomas16.minemate.integration;

import com.player2.playerengine.PlayerEngineController;

/** Direct control over the companion's legs, for the cases PlayerEngine leaves latched. */
public final class CompanionMovement {

    private CompanionMovement() {
    }

    /**
     * Cancels pathing and drops every forced input.
     *
     * <p>PlayerEngine's forced-input map is sticky, and
     * {@code InputOverrideHandler.onTickServer} only rewrites the entity's
     * movement fields when something marked the map dirty. A task that is
     * interrupted rather than finished — for example when a higher-priority
     * chain preempts the user chain — never clears either, so the last impulse
     * keeps walking the entity in a straight line. This is the same pair
     * {@code UserTaskChain.onTaskFinish} uses, i.e. the engine's own stop.
     */
    public static void halt(PlayerEngineController controller) {
        controller.getBaritone().getPathingBehavior().forceCancel();
        controller.getBaritone().getInputOverrideHandler().clearAllKeys();
    }
}
