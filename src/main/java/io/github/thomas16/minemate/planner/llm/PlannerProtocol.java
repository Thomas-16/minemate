package io.github.thomas16.minemate.planner.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/** Hosted-planner-only protocol additions that must never become executable world tools. */
public final class PlannerProtocol {

    public static final String STATUS_TOOL = "report_order_status";
    public static final String CONTRACT_TOOL = "declare_order_contract";

    private PlannerProtocol() {
    }

    /** Adds the reserved protocol functions to a registry schema without changing the registry. */
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
        tools.add(contractTool());
        tools.add(statusTool());
        return tools.toString();
    }

    private static JsonObject statusTool() {
        JsonObject status = new JsonObject();
        status.addProperty("type", "string");
        status.addProperty("description", "Terminal order status; ACTION is not allowed here."
                + " COMPLETED closes a verified world order; ANSWERED closes a question or social"
                + " remark that needed no world action.");
        JsonArray statuses = new JsonArray();
        statuses.add("COMPLETED");
        statuses.add("ANSWERED");
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

    /**
     * The contract declaration for a new order: the final deliverables and whether they must reach
     * the owner. Deterministic code validates the accompanying world calls against it.
     *
     * <p>The nested deliverable object is written pre-strict — its own {@code required} list and
     * {@code additionalProperties: false} — because the strict-mode transform rewrites only the top
     * level of a tool's parameters and does not recurse.
     */
    private static JsonObject contractTool() {
        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("description", "Plain Minecraft item id, e.g. 'iron_sword'.");

        JsonObject count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("minimum", 1);
        count.addProperty("maximum", 64);
        count.addProperty("description", "Desired total the owner should end up with.");

        JsonObject deliverableProperties = new JsonObject();
        deliverableProperties.add("item", item);
        deliverableProperties.add("count", count);

        JsonArray deliverableRequired = new JsonArray();
        deliverableRequired.add("item");
        deliverableRequired.add("count");

        JsonObject deliverable = new JsonObject();
        deliverable.addProperty("type", "object");
        deliverable.add("properties", deliverableProperties);
        deliverable.add("required", deliverableRequired);
        deliverable.addProperty("additionalProperties", false);

        JsonObject deliverables = new JsonObject();
        deliverables.addProperty("type", "array");
        deliverables.addProperty("description", "Every final item the owner asked to receive, with"
                + " desired totals. Empty when the order asks for no items.");
        deliverables.addProperty("maxItems", 8);
        deliverables.add("items", deliverable);

        JsonObject deliverToOwner = new JsonObject();
        deliverToOwner.addProperty("type", "boolean");
        deliverToOwner.addProperty("description",
                "True when the deliverables must end up in the owner's hands.");

        JsonObject properties = new JsonObject();
        properties.add("deliverables", deliverables);
        properties.add("deliver_to_owner", deliverToOwner);

        JsonArray required = new JsonArray();
        required.add("deliverables");
        required.add("deliver_to_owner");

        JsonObject input = new JsonObject();
        input.addProperty("type", "object");
        input.add("properties", properties);
        input.add("required", required);

        JsonObject tool = new JsonObject();
        tool.addProperty("name", CONTRACT_TOOL);
        tool.addProperty("description", "Declare what the current order requires before acting."
                + " Call it exactly once alongside the first action reply for an order; never with"
                + " a terminal status, and do not repeat it on a replan.");
        tool.add("input_schema", input);
        return tool;
    }
}
