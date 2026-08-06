package io.github.thomas16.minemate.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.player2.playerengine.TaskCatalogue;

import io.github.thomas16.minemate.task.ExecutionScope;
import io.github.thomas16.minemate.task.Goal;
import io.github.thomas16.minemate.task.Goals;
import io.github.thomas16.minemate.task.ItemQuantity;
import io.github.thomas16.minemate.task.Reasons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The single source of truth for what the companion can be asked to do.
 *
 * <p>Chat commands and the planner both go through {@link #validate}, so a tool cannot behave one way
 * when typed and another way when planned. Validation here is pure — it never touches the world — which
 * lets a whole plan be checked before any part of it is submitted. World-dependent binding happens
 * later, in {@link ToolDescriptor.GoalBuilder}.
 */
public final class ToolRegistry {

    private static final int MAX_COUNT = 2304;
    private static final int MAX_ITEM_ENTRIES = 8;
    private static final int WORLD_LIMIT = 30_000_000;

    private static ToolRegistry defaults;

    private final Map<String, ToolDescriptor> byName;
    private String schemaJson;

    private ToolRegistry(List<ToolDescriptor> tools) {
        Map<String, ToolDescriptor> map = new LinkedHashMap<>();
        for (ToolDescriptor tool : tools) {
            if (map.put(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate tool " + tool.name());
            }
        }
        this.byName = Map.copyOf(map);
    }

    /** Server-thread-only lazy singleton; building it touches PlayerEngine's catalogue. */
    public static ToolRegistry defaults() {
        if (defaults == null) {
            defaults = new ToolRegistry(builtIn());
        }
        return defaults;
    }

    public List<ToolDescriptor> tools() {
        return List.copyOf(byName.values());
    }

    public Optional<ToolDescriptor> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * Checks a call against the schema. Pure: no world access, so a planner's whole plan can be
     * validated up front and rejected without any side effect.
     */
    public ToolValidation validate(ToolCall call) {
        ToolDescriptor tool = byName.get(call.tool());
        if (tool == null) {
            return new ToolValidation.Rejected(Reasons.UNKNOWN_TOOL, call.tool());
        }
        // Reject unknown keys rather than ignoring them: a silently dropped argument hides a
        // planner prompt bug behind a goal that quietly does the wrong thing.
        for (String key : call.arguments().keySet()) {
            if (tool.parameter(key).isEmpty()) {
                return new ToolValidation.Rejected(Reasons.UNKNOWN_ARGUMENT, key);
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (ToolParam param : tool.parameters()) {
            JsonElement raw = call.arguments().get(param.name());
            if (raw == null || raw.isJsonNull()) {
                if (param.required()) {
                    return new ToolValidation.Rejected(Reasons.MISSING_ARGUMENT, param.name());
                }
                if (param.defaultValue() != null) {
                    values.put(param.name(), param.defaultValue());
                }
                continue;
            }
            try {
                values.put(param.name(), coerce(param, raw));
            } catch (Invalid invalid) {
                return new ToolValidation.Rejected(invalid.reason, invalid.detail);
            }
        }
        return new ToolValidation.Accepted(tool, new ToolArguments(values));
    }

    private static Object coerce(ToolParam param, JsonElement raw) {
        return switch (param.type()) {
            case INT -> {
                double value = number(param, raw);
                if (value != Math.floor(value)) {
                    yield reject(Reasons.INVALID_ARGUMENT, param.name() + " must be a whole number");
                }
                yield (int) bounded(param, value);
            }
            case NUMBER -> bounded(param, number(param, raw));
            case RESOURCE -> {
                String name = string(param, raw);
                if (!TaskCatalogue.taskExists(name)) {
                    yield reject(Reasons.UNKNOWN_RESOURCE, name);
                }
                yield name;
            }
            case ITEM -> {
                String name = string(param, raw);
                ResourceLocation id = ResourceLocation.tryParse(name);
                Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                if (item == null) {
                    yield reject(Reasons.UNKNOWN_ITEM, name);
                }
                yield item;
            }
            case PLAYER -> {
                String name = string(param, raw);
                if (name.isBlank()) {
                    yield reject(Reasons.INVALID_ARGUMENT, param.name() + " must not be blank");
                }
                yield name;
            }
            case ENTITY -> {
                String name = string(param, raw);
                try {
                    yield UUID.fromString(name);
                } catch (IllegalArgumentException error) {
                    yield reject(Reasons.INVALID_ARGUMENT, param.name() + " must be an entity UUID");
                }
            }
            case ITEM_LIST -> {
                if (!raw.isJsonArray()) {
                    yield reject(Reasons.INVALID_ARGUMENT,
                            param.name() + " must be an array of {item, count} entries");
                }
                JsonArray array = raw.getAsJsonArray();
                if (array.isEmpty() || array.size() > MAX_ITEM_ENTRIES) {
                    yield reject(Reasons.ARGUMENT_OUT_OF_RANGE,
                            param.name() + " must contain 1 to " + MAX_ITEM_ENTRIES + " entries");
                }
                Map<String, Integer> merged = new LinkedHashMap<>();
                for (JsonElement element : array) {
                    if (!element.isJsonObject()) {
                        reject(Reasons.INVALID_ARGUMENT,
                                param.name() + " entries must be {item, count} objects");
                    }
                    JsonObject entry = element.getAsJsonObject();
                    // Unknown keys reject rather than vanish, for the same reason as validate().
                    for (String key : entry.keySet()) {
                        if (!"item".equals(key) && !"count".equals(key)) {
                            reject(Reasons.UNKNOWN_ARGUMENT, key);
                        }
                    }
                    JsonElement itemRaw = entry.get("item");
                    if (itemRaw == null || !itemRaw.isJsonPrimitive()
                            || !itemRaw.getAsJsonPrimitive().isString()) {
                        reject(Reasons.INVALID_ARGUMENT,
                                param.name() + " entry item must be a resource name string");
                    }
                    String itemName = itemRaw.getAsString().strip();
                    if (!TaskCatalogue.taskExists(itemName)) {
                        reject(Reasons.UNKNOWN_RESOURCE, itemName);
                    }
                    JsonElement countRaw = entry.get("count");
                    if (countRaw == null || countRaw.isJsonNull()) {
                        reject(Reasons.MISSING_ARGUMENT, "count for " + itemName);
                    }
                    double value = number(param, countRaw);
                    if (value != Math.floor(value)) {
                        reject(Reasons.INVALID_ARGUMENT,
                                param.name() + " entry count must be a whole number");
                    }
                    int total = merged.merge(itemName, (int) value, Integer::sum);
                    if ((int) value < 1 || total > MAX_COUNT) {
                        reject(Reasons.ARGUMENT_OUT_OF_RANGE,
                                "count must be between 1 and " + MAX_COUNT + " for " + itemName);
                    }
                }
                yield merged.entrySet().stream()
                        .map(e -> new ItemQuantity(e.getKey(), e.getValue()))
                        .toList();
            }
            case BLOCK_POS -> {
                if (!raw.isJsonArray() || raw.getAsJsonArray().size() != 3) {
                    yield reject(Reasons.INVALID_ARGUMENT, param.name() + " must be [x, y, z]");
                }
                JsonArray array = raw.getAsJsonArray();
                int[] parts = new int[3];
                for (int index = 0; index < 3; index++) {
                    double value = number(param, array.get(index));
                    if (value != Math.floor(value) || Math.abs(value) > WORLD_LIMIT) {
                        yield reject(Reasons.INVALID_ARGUMENT,
                                param.name() + " must be three whole block coordinates");
                    }
                    parts[index] = (int) value;
                }
                yield new BlockPos(parts[0], parts[1], parts[2]);
            }
        };
    }

    /** Numbers arriving as JSON strings are the most common real planner slip; coerce rather than reject. */
    private static double number(ToolParam param, JsonElement raw) {
        if (!raw.isJsonPrimitive()) {
            throw new Invalid(Reasons.INVALID_ARGUMENT, param.name() + " must be a number");
        }
        if (raw.getAsJsonPrimitive().isNumber()) {
            return finite(param, raw.getAsDouble());
        }
        if (raw.getAsJsonPrimitive().isString()) {
            try {
                return finite(param, Double.parseDouble(raw.getAsString().strip()));
            } catch (NumberFormatException error) {
                throw new Invalid(Reasons.INVALID_ARGUMENT, param.name() + " must be a number");
            }
        }
        throw new Invalid(Reasons.INVALID_ARGUMENT, param.name() + " must be a number");
    }

    /**
     * NaN and the infinities have to die here rather than in {@link #bounded}. Both range
     * comparisons are false for NaN, so an unfiltered "NaN" string would satisfy every bound and
     * reach pathfinding and progress arithmetic as a value no later check can recognise.
     */
    private static double finite(ToolParam param, double value) {
        if (!Double.isFinite(value)) {
            throw new Invalid(Reasons.INVALID_ARGUMENT, param.name() + " must be a finite number");
        }
        return value;
    }

    private static double bounded(ToolParam param, double value) {
        if (param.bounded() && (value < param.min() || value > param.max())) {
            throw new Invalid(Reasons.ARGUMENT_OUT_OF_RANGE, "%s must be between %s and %s".formatted(
                    param.name(), trim(param.min()), trim(param.max())));
        }
        return value;
    }

    private static String string(ToolParam param, JsonElement raw) {
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) {
            throw new Invalid(Reasons.INVALID_ARGUMENT, param.name() + " must be a string");
        }
        return raw.getAsString().strip();
    }

    private static <T> T reject(String reason, String detail) {
        throw new Invalid(reason, detail);
    }

    private static String trim(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** Internal control flow for {@link #coerce}; never escapes {@link #validate}. */
    private static final class Invalid extends RuntimeException {
        private final String reason;
        private final String detail;

        private Invalid(String reason, String detail) {
            super(detail, null, false, false);
            this.reason = reason;
            this.detail = detail;
        }
    }

    /**
     * Tool definitions for a planner prompt, as a JSON array of
     * {@code {name, description, input_schema}} objects.
     *
     * <p>Resource and item names are described in prose rather than enumerated: the catalogue holds
     * thousands of names and would swamp the prompt. Bad names come back as a typed
     * {@link Reasons#UNKNOWN_RESOURCE} rejection instead.
     */
    public String schemaJson() {
        if (schemaJson == null) {
            JsonArray array = new JsonArray();
            for (ToolDescriptor tool : byName.values()) {
                array.add(schemaOf(tool));
            }
            schemaJson = array.toString();
        }
        return schemaJson;
    }

    private static JsonObject schemaOf(ToolDescriptor tool) {
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (ToolParam param : tool.parameters()) {
            properties.add(param.name(), propertyOf(param));
            if (param.required()) {
                required.add(param.name());
            }
        }
        JsonObject input = new JsonObject();
        input.addProperty("type", "object");
        input.add("properties", properties);
        input.add("required", required);

        JsonObject entry = new JsonObject();
        entry.addProperty("name", tool.name());
        entry.addProperty("description", tool.summary());
        entry.add("input_schema", input);
        return entry;
    }

    private static JsonObject propertyOf(ToolParam param) {
        JsonObject property = new JsonObject();
        switch (param.type()) {
            case INT, NUMBER -> {
                property.addProperty("type", param.type() == ParamType.INT ? "integer" : "number");
                property.addProperty("minimum", param.min());
                property.addProperty("maximum", param.max());
            }
            case BLOCK_POS -> {
                JsonObject items = new JsonObject();
                items.addProperty("type", "integer");
                property.addProperty("type", "array");
                property.add("items", items);
                property.addProperty("minItems", 3);
                property.addProperty("maxItems", 3);
            }
            case ITEM_LIST -> {
                // Written pre-strict - the entry object carries its own required list and
                // additionalProperties: false - because the OpenAI strict transform rewrites only
                // the top level of a tool's parameters, and its null stripping is shallow, so
                // every nested field must be required. Anthropic takes this shape verbatim.
                JsonObject itemProperty = new JsonObject();
                itemProperty.addProperty("type", "string");
                itemProperty.addProperty("description", "Plain resource name, e.g. 'stone_pickaxe'.");
                JsonObject countProperty = new JsonObject();
                countProperty.addProperty("type", "integer");
                countProperty.addProperty("minimum", 1);
                countProperty.addProperty("maximum", MAX_COUNT);
                countProperty.addProperty("description", "How many the owner should receive in total.");
                JsonObject entryProperties = new JsonObject();
                entryProperties.add("item", itemProperty);
                entryProperties.add("count", countProperty);
                JsonArray entryRequired = new JsonArray();
                entryRequired.add("item");
                entryRequired.add("count");
                JsonObject entry = new JsonObject();
                entry.addProperty("type", "object");
                entry.add("properties", entryProperties);
                entry.add("required", entryRequired);
                entry.addProperty("additionalProperties", false);
                property.addProperty("type", "array");
                property.add("items", entry);
                property.addProperty("minItems", 1);
                property.addProperty("maxItems", MAX_ITEM_ENTRIES);
            }
            default -> property.addProperty("type", "string");
        }
        property.addProperty("description", param.description());
        return property;
    }

    private static List<ToolDescriptor> builtIn() {
        ToolParam count = ToolParam.optionalInt("count", 1, MAX_COUNT, 1, "How many to end up holding.");
        return List.of(
                new ToolDescriptor("mine", "Gather a resource from the world, acquiring any tools it needs first.",
                        ExecutionScope.COMPOSITE,
                        List.of(ToolParam.resource("resource",
                                "Resource name, e.g. 'dirt', 'iron_ore', 'oak_log'."), count),
                        (args, ctx) -> Goals.mine(args.getString("resource"), args.getInt("count"))),

                new ToolDescriptor("fetch", "Acquire a resource if necessary, then deliver it to the owner; "
                        + "items already held are delivered too.",
                        ExecutionScope.COMPOSITE,
                        List.of(ToolParam.resource("resource", "Resource to acquire if needed and deliver, "
                                + "including one already held."), count),
                        (args, ctx) -> Goals.fetch(args.getString("resource"), args.getInt("count"))),

                new ToolDescriptor("fetch_items", "Deliver a set of final items to the owner in one "
                        + "order: each item is acquired or crafted if needed, then delivered in "
                        + "sequence; an item that proves unavailable is skipped and reported at the end.",
                        ExecutionScope.COMPOSITE,
                        List.of(ToolParam.itemList("items",
                                "The final deliverables as {item, count} entries, e.g. a full tool set.")),
                        (args, ctx) -> Goals.fetchItems(args.getItemQuantities("items"))),

                new ToolDescriptor("craft", "Craft an item, gathering and crafting its ingredients as needed; "
                        + "the output remains with the companion.",
                        ExecutionScope.COMPOSITE,
                        List.of(ToolParam.item("item", "Item id to craft, e.g. 'oak_planks'."), count),
                        (args, ctx) -> Goals.craft(ctx.companion(), args.getItem("item"), args.getInt("count"))),

                new ToolDescriptor("smelt", "Smelt an input item in a furnace until holding the output.",
                        ExecutionScope.DIRECT,
                        List.of(ToolParam.item("item", "Input item id to smelt, e.g. 'cobblestone'."), count),
                        (args, ctx) -> Goals.smelt(ctx.companion(), args.getItem("item"), args.getInt("count"))),

                new ToolDescriptor("deposit", "Put items into a specific container.",
                        ExecutionScope.DIRECT,
                        List.of(ToolParam.blockPos("container", "Container position as [x, y, z]."),
                                ToolParam.item("item", "Item id to deposit."),
                                ToolParam.optionalInt("count", 1, MAX_COUNT, 1,
                                        "How many the container should end up holding.")),
                        (args, ctx) -> Goals.deposit(ctx.companion(), args.getBlockPos("container"),
                                args.getItem("item"), args.getInt("count"))),

                new ToolDescriptor("withdraw", "Take items out of a specific container.",
                        ExecutionScope.DIRECT,
                        List.of(ToolParam.blockPos("container", "Container position as [x, y, z]."),
                                ToolParam.item("item", "Item id to withdraw."), count),
                        (args, ctx) -> Goals.withdraw(ctx.companion(), args.getBlockPos("container"),
                                args.getItem("item"), args.getInt("count"))),

                new ToolDescriptor("goto", "Travel to a horizontal position.",
                        ExecutionScope.DIRECT,
                        List.of(ToolParam.requiredInt("x", -WORLD_LIMIT, WORLD_LIMIT, "Target block X."),
                                ToolParam.requiredInt("z", -WORLD_LIMIT, WORLD_LIMIT, "Target block Z."),
                                ToolParam.optionalNumber("radius", 0.5, 64.0, 2.5,
                                        "How close counts as arrived, in blocks.")),
                        (args, ctx) -> Goals.goTo(args.getInt("x"), args.getInt("z"), args.getDouble("radius"))),

                new ToolDescriptor("come_to_owner", "Walk to the owner's current position, tracking "
                        + "them if they move, and finish once within the given distance. Use this "
                        + "whenever the owner asks the companion to come to them; it completes on "
                        + "arrival.",
                        ExecutionScope.DIRECT,
                        List.of(ToolParam.optionalNumber("distance", 1.0, 32.0, 2.5,
                                "How close counts as arrived, in blocks.")),
                        ToolRegistry::buildComeToOwner),

                new ToolDescriptor("kill", "Attack a specific entity until it dies.",
                        ExecutionScope.DIRECT,
                        List.of(ToolParam.entity("target", "UUID of the entity to attack.")),
                        ToolRegistry::buildKill),

                new ToolDescriptor("follow", "Follow a player continuously. Never finishes on its own, "
                        + "so it may only be the last step of a plan.",
                        ExecutionScope.CONTINUOUS,
                        List.of(ToolParam.player("player", false, "Player name; defaults to the owner."),
                                ToolParam.optionalNumber("distance", 0.0, 32.0, 3.0,
                                        "How far behind to trail, in blocks.")),
                        ToolRegistry::buildFollow));
    }

    /**
     * The self/owner guard lives here rather than only in the command layer, so no planner-issued
     * call can turn the companion on its owner.
     */
    private static Goal buildKill(ToolArguments args, ToolContext ctx) {
        UUID targetId = args.getUuid("target");
        if (ctx.companion().getUUID().equals(targetId)) {
            throw new ToolBindException(Reasons.INVALID_ARGUMENT, "The companion cannot attack itself");
        }
        if (ctx.companion().getOwnerUuid().filter(targetId::equals).isPresent()) {
            throw new ToolBindException(Reasons.INVALID_ARGUMENT, "The companion will not attack its owner");
        }
        Entity target = ctx.level().getEntity(targetId);
        if (target == null || target.isRemoved() || !target.isAlive()) {
            throw new ToolBindException(Reasons.TARGET_GONE, "That entity is no longer here");
        }
        return Goals.killEntity(ctx.level(), targetId, target.getName().getString());
    }

    private static Goal buildComeToOwner(ToolArguments args, ToolContext ctx) {
        ServerPlayer owner = ctx.companion().resolveOwnerInLevel().orElse(null);
        if (owner == null) {
            throw new ToolBindException(Reasons.OWNER_OFFLINE, "The owner is not reachable in this world");
        }
        return Goals.comeToOwner(owner, args.getDouble("distance"));
    }

    private static Goal buildFollow(ToolArguments args, ToolContext ctx) {
        ServerPlayer target = args.has("player")
                ? ctx.server().getPlayerList().getPlayerByName(args.getString("player"))
                : ctx.owner().orElse(null);
        if (target == null) {
            throw new ToolBindException(Reasons.OWNER_OFFLINE, "That player is not online");
        }
        return Goals.follow(target, args.getDouble("distance"));
    }
}
