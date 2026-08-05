package io.github.thomas16.minemate.task;

import com.player2.playerengine.tasks.CraftInInventoryTask;
import com.player2.playerengine.tasks.agentic.MineBlockTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.construction.DestroyBlockTask;
import com.player2.playerengine.tasks.container.CraftInTableTask;
import com.player2.playerengine.tasks.container.SmeltInFurnaceTask;
import com.player2.playerengine.tasks.container.SmeltInSmokerTask;
import com.player2.playerengine.tasks.cooking.SmeltDeferredTask;
import com.player2.playerengine.tasks.crafting.CraftMacroPhase;
import com.player2.playerengine.tasks.crafting.CraftMacroResourceTask;
import com.player2.playerengine.tasks.entity.GiveItemToPlayerTask;
import com.player2.playerengine.tasks.movement.ApproachNearBlockPosTask;
import com.player2.playerengine.tasks.movement.GetCloseToBlockTask;
import com.player2.playerengine.tasks.movement.GetToBlockTask;
import com.player2.playerengine.tasks.movement.GetToEntityTask;
import com.player2.playerengine.tasks.movement.GetToXZTask;
import com.player2.playerengine.tasks.movement.GetWithinRangeOfBlockTask;
import com.player2.playerengine.tasks.movement.SearchChunksExploreTask;
import com.player2.playerengine.tasks.movement.TimeoutWanderTask;
import com.player2.playerengine.tasks.resources.MineAndCollectTask;

/**
 * Maps a live PlayerEngine task chain to one player-facing phase word. The ladder prefers the
 * activity a player would name watching the companion: delivery and furnace work outrank the
 * mining umbrella, and search outranks it too, because {@code MineAndCollectTask} stays on the
 * chain for a whole ore episode while its sub-task alternates between hunting a target and
 * harvesting one. Phase words are episode-stable on purpose; the chat sink resends on every text
 * change, so a per-block vocabulary would spam the owner.
 */
final class TaskPhases {

    private TaskPhases() {
    }

    static String phase(Task root) {
        if (root == null) {
            return "collecting";
        }
        Flags flags = new Flags();
        try {
            root.thisOrChildSatisfies(task -> {
                collect(task, flags);
                return false;
            });
        } catch (RuntimeException e) {
            return "collecting";
        }
        if (flags.delivering) {
            return "delivering";
        }
        if (flags.smelting) {
            return "smelting";
        }
        if (flags.craftingAtStation) {
            return "crafting";
        }
        if (flags.searching) {
            return "searching";
        }
        if (flags.mining) {
            return "mining";
        }
        if (flags.macroPhase != null) {
            switch (flags.macroPhase) {
                case MOVE_TO_TABLE, FIND_OR_PLACE_TABLE -> {
                    return "approaching";
                }
                case CRAFT_2X2_INTERMEDIATES, LOOK_AT_TABLE, CRAFT_3X3_OUTPUT, VERIFY_OUTPUT, DONE -> {
                    return "crafting";
                }
                default -> {
                }
            }
        }
        if (flags.approaching) {
            return "approaching";
        }
        return "collecting";
    }

    private static void collect(Task task, Flags flags) {
        if (task instanceof GiveItemToPlayerTask) {
            flags.delivering = true;
        } else if (task instanceof SmeltDeferredTask || task instanceof SmeltInFurnaceTask
                || task instanceof SmeltInSmokerTask) {
            flags.smelting = true;
        } else if (task instanceof CraftInTableTask || task instanceof CraftInInventoryTask) {
            flags.craftingAtStation = true;
        } else if (task instanceof SearchChunksExploreTask || task instanceof TimeoutWanderTask) {
            flags.searching = true;
        } else if (task instanceof MineAndCollectTask || task instanceof MineBlockTask
                || task instanceof DestroyBlockTask) {
            flags.mining = true;
        } else if (task instanceof CraftMacroResourceTask macro) {
            flags.macroPhase = macro.getPhase();
        } else if (task instanceof GetToBlockTask || task instanceof GetToXZTask
                || task instanceof GetToEntityTask || task instanceof GetCloseToBlockTask
                || task instanceof GetWithinRangeOfBlockTask
                || task instanceof ApproachNearBlockPosTask) {
            flags.approaching = true;
        }
    }

    private static final class Flags {
        boolean delivering;
        boolean smelting;
        boolean craftingAtStation;
        boolean searching;
        boolean mining;
        boolean approaching;
        CraftMacroPhase macroPhase;
    }
}
