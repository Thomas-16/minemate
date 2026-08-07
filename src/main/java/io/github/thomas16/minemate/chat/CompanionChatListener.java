package io.github.thomas16.minemate.chat;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.entity.CompanionDirectory;
import io.github.thomas16.minemate.entity.CompanionEntity;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.Optional;

/**
 * Routes ordinary Minecraft chat from an owner to their companion. Observe-only on
 * {@code CHAT_MESSAGE}: public chat displays exactly as vanilla wrote it, and the companion
 * simply also hears what it was close enough -- or explicitly addressed enough -- to hear.
 *
 * <p>A message routes when the companion is within earshot (unless {@code chatRequiresName}), or
 * when it begins with the companion's complete name plus a word boundary; the name prefix is
 * addressing data and is stripped before the turn reaches the model. Exact stop forms after the
 * name take the deterministic stop path with no model call at all.
 */
public final class CompanionChatListener {

    private CompanionChatListener() {
    }

    public static void init() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
                handle(sender.getServer(), sender, message.signedContent()));
    }

    /** The routing entry point; public so the headless suite can drive it with a FakePlayer. */
    public static void handle(MinecraftServer server, ServerPlayer sender, String raw) {
        if (server == null || sender == null || raw == null || raw.isBlank()) {
            return;
        }
        CompanionDirectory directory = CompanionDirectory.of(server);
        Optional<CompanionEntity> loaded = directory.loadedCompanionOf(sender.getUUID());
        Optional<CompanionDirectory.Entry> entry = directory.entryOf(sender.getUUID());
        if (loaded.isEmpty() && entry.isEmpty()) {
            // Not this companion's owner; their chat is none of our business.
            return;
        }
        String name = loaded.map(companion -> companion.getCompanionName().orElse(""))
                .orElseGet(() -> entry.map(CompanionDirectory.Entry::companionName).orElse(""));
        String remainder = addressedRemainder(raw, name);
        boolean addressed = remainder != null;

        if (loaded.isPresent()) {
            CompanionEntity companion = loaded.get();
            if (addressed && isStopForm(remainder)) {
                // Deterministic and immediate: stopping must never wait on an API call.
                CompanionStop.stop(companion);
                CompanionSpeech.send(companion, "Stopping.");
                return;
            }
            boolean eligible = addressed
                    || (!MineMate.plannerConfig().chatRequiresName()
                            && companion.level() == sender.level()
                            && withinEarshot(companion, sender));
            if (!eligible) {
                return;
            }
            String turn = addressed ? remainder : raw;
            if (turn.isBlank()) {
                return;
            }
            companion.getConversationCoordinator().submitOwnerTurn(turn);
            return;
        }
        if (addressed) {
            // Loaded nowhere: a deterministic line from the last-known record, no model call,
            // and no force-loading of arbitrary chunks. Not companion speech -- the entity does
            // not exist right now, so nothing may enter its conversation memory.
            CompanionDirectory.Entry known = entry.get();
            sender.sendSystemMessage(Component.literal(
                    known.companionName() + " is away near (" + known.lastKnownPos().getX()
                            + ", " + known.lastKnownPos().getY() + ", "
                            + known.lastKnownPos().getZ() + ") in "
                            + known.dimension().location() + "."));
        }
    }

    /**
     * The text after a complete case-insensitive name prefix and a word boundary (end of line, or
     * any non-alphanumeric character, with one optional comma consumed), or null when the line
     * does not address the companion. No regex: names are arbitrary player strings.
     */
    static String addressedRemainder(String raw, String name) {
        if (name == null || name.isBlank() || raw.length() < name.length()) {
            return null;
        }
        if (!raw.regionMatches(true, 0, name, 0, name.length())) {
            return null;
        }
        if (raw.length() == name.length()) {
            return "";
        }
        if (Character.isLetterOrDigit(raw.charAt(name.length()))) {
            // "Ada Stones, come" does not address Ada Stone.
            return null;
        }
        String rest = raw.substring(name.length()).strip();
        if (rest.startsWith(",")) {
            rest = rest.substring(1).strip();
        }
        return rest;
    }

    /** Exact forms only: "cancel that fetch" is conversation for the model, not a reflex. */
    private static boolean isStopForm(String remainder) {
        String normalized = remainder.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("stop") || normalized.equals("stop that")
                || normalized.equals("cancel") || normalized.equals("cancel that");
    }

    private static boolean withinEarshot(CompanionEntity companion, ServerPlayer sender) {
        int earshot = MineMate.plannerConfig().chatEarshotBlocks();
        return companion.distanceToSqr(sender) <= (double) earshot * (double) earshot;
    }
}
