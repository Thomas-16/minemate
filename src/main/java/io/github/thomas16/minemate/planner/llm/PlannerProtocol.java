package io.github.thomas16.minemate.planner.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/** Hosted-planner-only protocol additions that must never become executable world tools. */
public final class PlannerProtocol {

    public static final String STATUS_TOOL = "report_order_status";

    private PlannerProtocol() {
    }

    /** Adds the reserved status function to a registry schema without changing the registry. */
    public static String hostedSchema(String worldSchemaJson) {
        JsonElement root;
        try {
            root = JsonParser.parseString(worldSchemaJson == null ? "" : worldSchemaJson);
        } catch (JsonSyntaxException error) {
            throw LlmException.malformed("tool schema did not parse");
        }
        if (root == null || !root.isJsonArray()) {
            throw LlmException.malformed("tool schema was not an array");
        }
        JsonArray tools = root.getAsJsonArray().deepCopy();
        tools.add(statusTool());
        return tools.toString();
    }

    private static JsonObject statusTool() {
        JsonObject status = new JsonObject();
        status.addProperty("type", "string");
        status.addProperty("description", "Terminal order status; ACTION is not allowed here.");
        JsonArray statuses = new JsonArray();
        statuses.add("COMPLETED");
        statuses.add("CLARIFICATION");
        statuses.add("UNSUPPORTED");
        statuses.add("BLOCKED");
        status.add("enum", statuses);

        JsonObject message = new JsonObject();
        message.addProperty("type", "string");
        message.addProperty("description", "One short plain-ASCII sentence to tell the owner.");

        JsonObject properties = new JsonObject();
        properties.add("status", status);
        properties.add("message", message);

        JsonArray required = new JsonArray();
        required.add("status");
        required.add("message");

        JsonObject input = new JsonObject();
        input.addProperty("type", "object");
        input.add("properties", properties);
        input.add("required", required);

        JsonObject tool = new JsonObject();
        tool.addProperty("name", STATUS_TOOL);
        tool.addProperty("description", "Close the current order with an explicit terminal status.");
        tool.add("input_schema", input);
        return tool;
    }
}
