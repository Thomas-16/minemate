package io.github.thomas16.minemate.tool;

import java.util.Objects;

/**
 * One declared parameter of a tool: its wire name, value kind, bounds, and prose description.
 *
 * <p>{@code min}/{@code max} apply only to {@link ParamType#INT} and {@link ParamType#NUMBER};
 * they are ignored for every other kind. {@code defaultValue} is null for required parameters.
 */
public record ToolParam(String name, ParamType type, boolean required, Object defaultValue,
        double min, double max, String description) {

    public ToolParam {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(description, "description");
        if (required && defaultValue != null) {
            throw new IllegalArgumentException("Required parameter " + name + " cannot have a default");
        }
    }

    public boolean bounded() {
        return type == ParamType.INT || type == ParamType.NUMBER;
    }

    public static ToolParam resource(String name, String description) {
        return new ToolParam(name, ParamType.RESOURCE, true, null, 0.0, 0.0, description);
    }

    public static ToolParam item(String name, String description) {
        return new ToolParam(name, ParamType.ITEM, true, null, 0.0, 0.0, description);
    }

    public static ToolParam blockPos(String name, String description) {
        return new ToolParam(name, ParamType.BLOCK_POS, true, null, 0.0, 0.0, description);
    }

    public static ToolParam itemList(String name, String description) {
        return new ToolParam(name, ParamType.ITEM_LIST, true, null, 0.0, 0.0, description);
    }

    public static ToolParam requiredInt(String name, int min, int max, String description) {
        return new ToolParam(name, ParamType.INT, true, null, min, max, description);
    }

    public static ToolParam optionalInt(String name, int min, int max, int defaultValue, String description) {
        return new ToolParam(name, ParamType.INT, false, defaultValue, min, max, description);
    }

    public static ToolParam optionalNumber(String name, double min, double max, double defaultValue,
            String description) {
        return new ToolParam(name, ParamType.NUMBER, false, defaultValue, min, max, description);
    }

    public static ToolParam player(String name, boolean required, String description) {
        return new ToolParam(name, ParamType.PLAYER, required, null, 0.0, 0.0, description);
    }

    public static ToolParam entity(String name, String description) {
        return new ToolParam(name, ParamType.ENTITY, true, null, 0.0, 0.0, description);
    }
}
