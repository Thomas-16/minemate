package io.github.thomas16.minemate.tool;

/**
 * The value kinds a tool parameter can carry across the JSON boundary.
 *
 * <p>Every kind has exactly one JSON encoding, one validation rule, and one accessor on
 * {@link ToolArguments}, so the chat commands and the planner cannot disagree about what a
 * value means.
 */
public enum ParamType {
    /** A PlayerEngine {@code TaskCatalogue} resource name, e.g. {@code "iron_ore"}. */
    RESOURCE,
    /** A registry item id, e.g. {@code "minecraft:oak_planks"} or {@code "oak_planks"}. */
    ITEM,
    /** A three-element integer array {@code [x, y, z]}. */
    BLOCK_POS,
    INT,
    NUMBER,
    /**
     * An array of {@code {item, count}} objects, where {@code item} is a PlayerEngine
     * {@code TaskCatalogue} resource name and {@code count} is a positive integer. Duplicate
     * items are merged by summing their counts.
     */
    ITEM_LIST,
    /** A player name, resolved when the goal is built rather than when the call is validated. */
    PLAYER,
    /** An entity UUID string, resolved when the goal is built. */
    ENTITY
}
