package io.github.thomas16.minemate.planner.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import io.github.thomas16.minemate.planner.OrderContract;
import io.github.thomas16.minemate.planner.Plan;
import io.github.thomas16.minemate.planner.PlanDisposition;
import io.github.thomas16.minemate.tool.ToolCall;

import java.util.ArrayList;
import java.util.List;

/** Hosted-planner-only protocol additions that must never become executable world tools. */
public final class PlannerProtocol {

    public static final String STATUS_TOOL = "report_order_status";
    public static final String CONTRACT_TOOL = "declare_order_contract";

    private PlannerProtocol() {
    }

    /**
     * A provider-parsed call: the name and its arguments as a plain JSON object. Each backend gets
     * there its own way -- OpenAI re-parses an arguments <em>string</em> and strips strict-mode
     * nulls, Anthropic hands the object over directly -- and everything after that point is
     * protocol, not wire format.
     */
    public record RawCall(String name, JsonObject arguments) {
    }

    /**
     * Turns a provider's parsed reply into an {@link LlmReply}, enforcing every protocol rule in
     * one place so two backends cannot drift apart: at most one status and one contract, a status
     * never mixes with world calls, a contract, or a separate assistant message, an action reply
     * needs speech and at most {@link Plan#MAX_STEPS} world calls, and the contract-only shape
     * stays parseable so dispatch can repair it.
     *
     * @param dialogue   the concatenated assistant text, possibly blank
     * @param sawMessage whether any assistant text item existed at all, even an empty one; a
     *                   terminal reply must not carry one
     */
    public static LlmReply assemble(String dialogue, boolean sawMessage, List<RawCall> calls) {
        List<RawCall> statuses = new ArrayList<>();
        List<RawCall> contracts = new ArrayList<>();
        List<RawCall> worldCalls = new ArrayList<>();
        for (RawCall call : calls) {
            if (STATUS_TOOL.equals(call.name())) {
                statuses.add(call);
            } else if (CONTRACT_TOOL.equals(call.name())) {
                contracts.add(call);
            } else {
                worldCalls.add(call);
            }
        }

        if (statuses.size() > 1) {
            throw LlmException.malformed("reply contained multiple order-status calls");
        }
        if (contracts.size() > 1) {
            throw LlmException.malformed("reply contained multiple order-contract calls");
        }
        if (statuses.size() == 1) {
            if (!worldCalls.isEmpty()) {
                throw LlmException.malformed("reply mixed an order status with world-tool calls");
            }
            if (!contracts.isEmpty()) {
                throw LlmException.malformed("terminal reply included an order contract");
            }
            if (sawMessage) {
                throw LlmException.malformed("terminal reply included a separate assistant message");
            }
            return statusReplyOf(statuses.get(0));
        }

        // The contract call is protocol, not work: it does not count toward the step bound, so a
        // contract plus MAX_STEPS world calls is a legal reply. A contract with no world calls is
        // parseable on purpose: single-call-habit models really send it, and dispatch turns it
        // into a repairable violation rather than an opaque parse failure.
        if (worldCalls.isEmpty() && contracts.isEmpty()) {
            throw LlmException.malformed("reply had no world-tool calls or order status");
        }
        if (worldCalls.size() > Plan.MAX_STEPS) {
            throw LlmException.malformed("reply contained more than " + Plan.MAX_STEPS
                    + " world-tool calls");
        }
        if (dialogue == null || dialogue.isBlank()) {
            throw LlmException.malformed("action reply had no assistant speech");
        }
        List<ToolCall> toolCalls = new ArrayList<>(worldCalls.size());
        for (RawCall call : worldCalls) {
            toolCalls.add(toolCallOf(call));
        }
        OrderContract contract = contracts.isEmpty() ? null : contractOf(contracts.get(0));
        return new LlmReply(dialogue, toolCalls, PlanDisposition.ACTION, contract);
    }

    private static ToolCall toolCallOf(RawCall call) {
        if (call.name() == null || call.name().isBlank()) {
            throw LlmException.malformed("function call had no name");
        }
        if (call.arguments() == null || call.arguments().size() == 0) {
            return ToolCall.of(call.name());
        }
        return new ToolCall(call.name(), call.arguments());
    }

    private static LlmReply statusReplyOf(RawCall call) {
        JsonObject arguments = call.arguments() == null ? new JsonObject() : call.arguments();
        if (arguments.size() != 2 || !arguments.has("status") || !arguments.has("message")) {
            throw LlmException.malformed("order status required exactly status and message");
        }
        String rawStatus = stringArgument(arguments, "status");
        PlanDisposition disposition;
        try {
            disposition = PlanDisposition.valueOf(rawStatus);
        } catch (IllegalArgumentException error) {
            throw LlmException.malformed("invalid order status: " + rawStatus);
        }
        if (!disposition.terminal()) {
            throw LlmException.malformed("ACTION is not a terminal order status");
        }
        String message = stringArgument(arguments, "message").strip();
        if (message.isBlank()) {
            throw LlmException.malformed("order status message was blank");
        }
        return new LlmReply(message, List.of(), disposition);
    }

    private static OrderContract contractOf(RawCall call) {
        JsonObject arguments = call.arguments() == null ? new JsonObject() : call.arguments();
        JsonElement deliverables = arguments.get("deliverables");
        JsonElement deliverToOwner = arguments.get("deliver_to_owner");
        if (arguments.size() != 2 || deliverables == null || !deliverables.isJsonArray()
                || deliverToOwner == null || !deliverToOwner.isJsonPrimitive()
                || !deliverToOwner.getAsJsonPrimitive().isBoolean()) {
            throw LlmException.malformed(
                    "order contract required exactly deliverables and deliver_to_owner");
        }
        List<OrderContract.Deliverable> parsed = new ArrayList<>();
        for (JsonElement element : deliverables.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw LlmException.malformed(
                        "order contract deliverable required an item and a positive count");
            }
            JsonObject deliverable = element.getAsJsonObject();
            String item = string(deliverable, "item");
            JsonElement count = deliverable.get("count");
            if (deliverable.size() != 2 || item.isBlank() || count == null
                    || !count.isJsonPrimitive() || !count.getAsJsonPrimitive().isNumber()
                    || count.getAsDouble() != Math.floor(count.getAsDouble())
                    || count.getAsInt() < 1) {
                throw LlmException.malformed(
                        "order contract deliverable required an item and a positive count");
            }
            parsed.add(new OrderContract.Deliverable(item, count.getAsInt()));
        }
        return new OrderContract(parsed, deliverToOwner.getAsBoolean());
    }

    private static String stringArgument(JsonObject arguments, String name) {
        JsonElement value = arguments.get(name);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw LlmException.malformed("order status " + name + " was not a string");
        }
        return value.getAsString();
    }

    private static String string(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
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
