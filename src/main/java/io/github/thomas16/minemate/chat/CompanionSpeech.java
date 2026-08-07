package io.github.thomas16.minemate.chat;

import io.github.thomas16.minemate.context.ConversationLog;
import io.github.thomas16.minemate.entity.CompanionEntity;

import net.minecraft.network.chat.Component;

import java.util.function.BiConsumer;

/**
 * The single gateway for everything presented as something the companion <em>said</em>.
 *
 * <p>Words go to the owner as a private, name-attributed line -- {@code Milo: Still smelting.} --
 * and exactly that displayed text is recorded as a {@code COMPANION_UTTERANCE} in the
 * conversation log, so memory and display can never disagree. Task lifecycle telemetry
 * ({@code [MineMate] Started: ...}) is deliberately not speech and stays out of both.
 *
 * <p>The utterance is recorded even when the owner is offline: the companion still said it, and
 * a transcript that silently loses lines the player never saw would still be the truthful record
 * of what the companion believes it communicated... but an unheard line is worse than that -- it
 * claims a shared exchange that never happened. Display and memory therefore stay coupled: record
 * always, because the send is attempted always; resolveOwner covers every reachable owner,
 * including headless fake players.
 */
public final class CompanionSpeech {

    /** Skip a resend of the identical line within this window; lifecycle sinks can double-fire. */
    private static final long DEDUP_WINDOW_TICKS = 100;

    /** Test observer receiving every displayed line; headless fake owners swallow chat packets. */
    private static volatile BiConsumer<CompanionEntity, String> observer;

    private CompanionSpeech() {
    }

    /** Speaks as the companion: displays the named line and remembers it. Blank is a no-op. */
    public static void send(CompanionEntity companion, String text) {
        if (companion == null || text == null || text.isBlank()) {
            return;
        }
        String name = companion.getCompanionName().orElse("Companion");
        String line = name + ": " + text.strip();
        ConversationLog log = companion.getConversationLog();
        long gameTime = companion.level().getGameTime();
        if (log != null && isDuplicate(log, line, gameTime)) {
            return;
        }
        companion.resolveOwner().ifPresent(owner ->
                owner.sendSystemMessage(Component.literal(line)));
        if (log != null) {
            log.recordCompanion(line, gameTime);
        }
        BiConsumer<CompanionEntity, String> watcher = observer;
        if (watcher != null) {
            watcher.accept(companion, line);
        }
    }

    private static boolean isDuplicate(ConversationLog log, String line, long gameTime) {
        ConversationLog.Entry last = log.lastEntry();
        return last != null
                && last.kind() == ConversationLog.EntryKind.COMPANION_UTTERANCE
                && last.text().equals(line)
                && gameTime - last.gameTime() <= DEDUP_WINDOW_TICKS;
    }

    /** Headless-test hook; null clears it. Fired after display with the exact displayed line. */
    public static void setObserver(BiConsumer<CompanionEntity, String> newObserver) {
        observer = newObserver;
    }
}
