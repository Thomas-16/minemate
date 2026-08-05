package io.github.thomas16.minemate.planner.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Translates the registry's neutral tool schema into OpenAI's strict function form.
 *
 * <p>The translation lives here rather than in {@code ToolRegistry} deliberately.
 * {@code ToolRegistry.schemaJson()} is the one description of what the companion can be asked to
 * do, and it is also what {@code /companion tools} prints; only the backend should have to know
 * that one particular provider spells a tool definition flat and demands total {@code required}
 * lists.
 *
 * <p>Three OpenAI rules drive everything below. Strict mode requires
 * {@code additionalProperties: false} on the object, requires <em>every</em> property to appear in
 * {@code required}, and expresses an optional parameter as a union with {@code null} rather than by
 * omission. The last one is why {@link OpenAiResponsesBackend} has to strip nulls back out of the
 * arguments it gets back: {@code ToolRegistry} rejects a null where it would happily have accepted
 * an absent key and filled in the parameter's default.
 */
public final class OpenAiToolSchemas {

    private static volatile String cachedInput;
    private static volatile JsonArray cachedOutput;

    private OpenAiToolSchemas() {
    }

    /**
     * @param registrySchemaJson a JSON array of {@code {name, description, input_schema}} objects
     * @return a JSON array of OpenAI {@code {type, name, description, parameters, strict}} objects
     */
    public static JsonArray toOpenAiTools(String registrySchemaJson) {
        // The registry memoizes its own output, so the same string arrives on every request.
        String input = registrySchemaJson == null ? "" : registrySchemaJson;
        JsonArray cached = cachedOutput;
        if (cached != null && input.equals(cachedInput)) {
            return cached.deepCopy();
        }
        JsonArray translated = translate(input);
        cachedInput = input;
        cachedOutput = translated;
        return translated.deepCopy();
    }

    private static JsonArray translate(String registrySchemaJson) {
        JsonElement root;
        try {
            root = JsonParser.parseString(registrySchemaJson);
        } catch (JsonSyntaxException error) {
            throw LlmException.malformed("tool schema did not parse");
        }
        if (root == null || !root.isJsonArray()) {
            throw LlmException.malformed("tool schema was not an array");
        }
        JsonArray tools = new JsonArray();
        for (JsonElement element : root.getAsJsonArray()) {
            if (element.isJsonObject()) {
                tools.add(toolOf(element.getAsJsonObject()));
            }
        }
        return tools;
    }

    private static JsonObject toolOf(JsonObject registryTool) {
        JsonObject tool = new JsonObject();
        // Flat, not nested under a "function" object. The nested form is Chat Completions' shape and
        // is silently wrong here: the request is rejected for a missing name.
        tool.addProperty("type", "function");
        tool.addProperty("name", string(registryTool, "name"));
        tool.addProperty("description", string(registryTool, "description"));
        tool.add("parameters", parametersOf(registryTool));
        tool.addProperty("strict", true);
        return tool;
    }

    private static JsonObject parametersOf(JsonObject registryTool) {
        JsonElement schema = registryTool.get("input_schema");
        JsonObject parameters = schema != null && schema.isJsonObject()
                ? schema.getAsJsonObject().deepCopy()
                : emptyObjectSchema();

        JsonObject properties = parameters.getAsJsonObject("properties");
        if (properties == null) {
            properties = new JsonObject();
            parameters.add("properties", properties);
        }

        Set<String> originallyRequired = names(parameters.getAsJsonArray("required"));

        JsonArray required = new JsonArray();
        for (String name : properties.keySet()) {
            required.add(name);
            if (!originallyRequired.contains(name)) {
                // Optional in the registry, so it has a default. Strict mode has no way to say
                // "may be absent", only "may be null", and the backend maps null back to absent.
                nullable(properties.getAsJsonObject(name));
            }
        }
        parameters.add("required", required);
        parameters.addProperty("additionalProperties", false);
        return parameters;
    }

    /**
     * Widens {@code "type": "integer"} to {@code "type": ["integer", "null"]}. Every other keyword
     * is left alone: {@code minimum}, {@code maximum}, {@code minItems}, {@code maxItems} and
     * {@code description} are all honoured under strict mode, so dropping them would throw away
     * real guardrails.
     */
    private static void nullable(JsonObject property) {
        if (property == null) {
            return;
        }
        JsonElement type = property.get("type");
        if (type == null || !type.isJsonPrimitive()) {
            return;
        }
        JsonArray union = new JsonArray();
        union.add(type.getAsString());
        union.add("null");
        property.add("type", union);
    }

    private static Set<String> names(JsonArray array) {
        Set<String> names = new LinkedHashSet<>();
        if (array != null) {
            for (JsonElement element : array) {
                if (element.isJsonPrimitive()) {
                    names.add(element.getAsString());
                }
            }
        }
        return names;
    }

    private static JsonObject emptyObjectSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    private static String string(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
