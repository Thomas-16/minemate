package io.github.thomas16.minemate.integration;

import com.player2.playerengine.player2api.config.Player2PayerMode;
import com.player2.playerengine.player2api.config.Player2ServerConfigAdminService;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;

import io.github.thomas16.minemate.MineMate;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Keeps PlayerEngine's hosted Player2 services dormant. PlayerEngine 1.4.x
 * defaults to payerMode=PROMPTER_PAYS, which makes every connected client
 * heartbeat against api.player2.game and prompt the player with an OAuth
 * device-flow link in chat. MineMate brings its own inference stack, so we
 * switch the mode to OWNER_PAYS_ALL — our companions have no Player2 owner,
 * which leaves the entire billing/auth path with nothing to do.
 */
public final class PlayerEngineQuiet {

    private PlayerEngineQuiet() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (Player2ServerConfigHolder.get().getPayerMode() != Player2PayerMode.PROMPTER_PAYS) {
                return;
            }
            Player2ServerConfigAdminService.UpdateResult result =
                    Player2ServerConfigAdminService.update(server, "payerMode", "OWNER_PAYS_ALL");
            MineMate.LOGGER.info("Set PlayerEngine payerMode=OWNER_PAYS_ALL to silence Player2 auth prompts: {}",
                    result);
        });
    }
}
