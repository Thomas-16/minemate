package io.github.thomas16.minemate.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One request to run one tool: a name plus a JSON argument object.
 *
 * <p>This is the only wire form. Chat commands re-encode their Brigadier-resolved arguments into it
 * so they exercise exactly the validation path a planner's JSON will hit; nothing gets a shortcut.
 */
public record ToolCall(String tool, JsonObject arguments) {

    public ToolCall {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(arguments, "arguments");
    }

    public static ToolCall of(String tool) {
        return new ToolCall(tool, new JsonObject());
    }

    /**
     * Reads an untrusted call, as a planner would emit it. Returns null for anything that is not a
     * JSON object carrying a string {@code tool} and, if present, an object {@code arguments}.
     */
    public static ToolCall parse(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject root = element.getAsJsonObject();
        JsonElement name = root.get("tool");
        if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) {
            return null;
        }
        JsonElement args = root.get("arguments");
        if (args == null || args.isJsonNull()) {
            return new ToolCall(name.getAsString(), new JsonObject());
        }
        return args.isJsonObject() ? new ToolCall(name.getAsString(), args.getAsJsonObject()) : null;
    }

    /** Reads a JSON array of calls. Returns null if the text is malformed or any element is not a call. */
    public static List<ToolCall> parseList(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonSyntaxException | IllegalStateException error) {
            return null;
        }
        if (root == null || !root.isJsonArray()) {
            return null;
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            ToolCall call = parse(element);
            if (call == null) {
                return null;
            }
            calls.add(call);
        }
        return calls;
    }

    public ToolCall with(String name, int value) {
        return withValue(name, new JsonPrimitive(value));
    }

    public ToolCall with(String name, double value) {
        return withValue(name, new JsonPrimitive(value));
    }

    public ToolCall with(String name, String value) {
        return withValue(name, new JsonPrimitive(value));
    }

    public ToolCall with(String name, Item value) {
        return withValue(name, new JsonPrimitive(BuiltInRegistries.ITEM.getKey(value).toString()));
    }

    public ToolCall with(String name, UUID value) {
        return withValue(name, new JsonPrimitive(value.toString()));
    }

    public ToolCall with(String name, BlockPos value) {
        JsonArray array = new JsonArray();
        array.add(value.getX());
        array.add(value.getY());
        array.add(value.getZ());
        return withValue(name, array);
    }

    private ToolCall withValue(String name, JsonElement value) {
        JsonObject copy = arguments.deepCopy();
        copy.add(name, value);
        return new ToolCall(tool, copy);
    }

    /** Stable single-line form for logs, the journal, and plan summaries. */
    public String toCompactString() {
        // Sorted so the same call always renders identically, whatever order the keys arrived in.
        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : arguments.entrySet()) {
            JsonElement value = entry.getValue();
            sorted.put(entry.getKey(),
                    value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                            ? value.getAsString() : value.toString());
        }
        StringBuilder text = new StringBuilder(tool).append('(');
        boolean first = true;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!first) {
                text.append(',');
            }
            text.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return text.append(')').toString();
    }
}
