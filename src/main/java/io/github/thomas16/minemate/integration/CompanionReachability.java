package io.github.thomas16.minemate.integration;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.helpers.LookHelper;

import io.github.thomas16.minemate.entity.CompanionEntity;

import net.minecraft.core.BlockPos;

/** Companion-scoped checks for whether PlayerEngine can really interact with a block. */
public final class CompanionReachability {

    private CompanionReachability() {
    }

    /**
     * Preserves PlayerEngine behavior for other entity types while requiring MineMate's
     * companion to have an unobstructed, in-range raycast to the target block.
     */
    public static boolean canReachBlock(PlayerEngineController controller, BlockPos target) {
        if (!(controller.getEntity() instanceof CompanionEntity)) {
            return true;
        }
        return target != null && LookHelper.getReach(controller, target).isPresent();
    }
}
