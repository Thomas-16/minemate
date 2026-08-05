package io.github.thomas16.minemate.planner.llm;

import io.github.thomas16.minemate.planner.Plan;
import io.github.thomas16.minemate.planner.PlanRequest;

import java.util.Locale;

/**
 * The words. Kept apart from the wire format so the prompt can be tuned without touching a mapper.
 *
 * <p>{@link #SYSTEM} must stay byte-identical across requests: it and the tool array are the cached
 * prefix, and any change invalidates the cache from that byte on. It is also plain ASCII, like every
 * other player-visible string in the mod, because a console or copy path may decode it.
 */
public final class PlannerPrompt {

    public static final String SYSTEM = """
            You are the planning brain of MineMate, a Minecraft companion serving one player.

            You receive a compact snapshot of the world and an order from that player. Reply with one
            short line of speech, then call the tools needed to carry the order out. Deterministic
            game code runs and verifies every tool call, so you decide what should happen, never how
            to move or swing.

            Rules:
            - Call at most %d tools. Prefer the shortest plan that satisfies the order. You will be
              asked again when it finishes, with the outcome, so do not try to plan the whole job in
              one go.
            - Tools run one at a time, in the order you call them. A later tool may rely on an
              earlier one having succeeded.
            - 'follow' never finishes on its own, so call it only as the very last tool, if at all.
            - If the order is already satisfied, or nothing useful can be done right now, reply with
              speech and call no tools. That is how you report that you are done.
            - Use plain Minecraft ids for resources and items: oak_log, cobblestone, iron_ore,
              oak_planks. Do not invent names; an unknown name fails the whole plan.
            - Keep speech to one short sentence, in character as a capable teammate rather than an
              assistant. Plain ASCII only, no emoji and no markdown.

            Reading the snapshot:
              SELF     health, food, armor, position, light level, block underfoot, held item
              WORLD    dimension and biome, game time, day phase, weather
              OWNER    the player you serve: distance, compass direction, height difference
              THREATS  hostile mobs; 'aggro' means it is targeting you, 'los' means it can see you
              NEAR     other players and villagers
              GEAR     equipped armor and offhand
              HOTBAR   the first nine inventory slots
              ITEMS    notable inventory beyond the hotbar
              NOW      what you are doing right now, with progress and plan step
              REFLEX   automatic behaviour running underneath you
              LAST     how the previous task ended
              EVENTS   recent events, newest first, each with its age

            A section is absent when it has nothing to report, or when it was dropped to fit the
            context budget. Absent never means empty.
            """.formatted(Plan.MAX_STEPS);

    private PlannerPrompt() {
    }

    /**
     * The volatile half of the prompt. Context comes first and the order last, so the instruction
     * the model must act on is the freshest thing it read.
     */
    public static String user(PlanRequest request) {
        StringBuilder text = new StringBuilder();
        if (!request.renderedContext().isBlank()) {
            text.append(request.renderedContext()).append("\n\n");
        }
        if (request.trigger() != null) {
            text.append("REPLAN (").append(request.trigger().name().toLowerCase(Locale.ROOT))
                    .append("): the order below is still open. Look at LAST and EVENTS, then either")
                    .append(" continue it or say you are done and call no tools.\n");
        }
        return text.append("ORDER: ").append(request.requestText()).toString();
    }
}
