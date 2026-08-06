package io.github.thomas16.minemate.tool;

import io.github.thomas16.minemate.task.ItemQuantity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Argument values that have already passed {@link ToolRegistry#validate}: every key is declared,
 * every type is correct, every bound holds, and optional parameters carry their defaults.
 *
 * <p>Only {@link ToolRegistry} constructs this, which is what lets a {@link ToolDescriptor.GoalBuilder}
 * read values without re-checking them.
 */
public record ToolArguments(Map<String, Object> values) {

    public ToolArguments {
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public int getInt(String name) {
        return require(name, Number.class).intValue();
    }

    public double getDouble(String name) {
        return require(name, Number.class).doubleValue();
    }

    public String getString(String name) {
        return require(name, String.class);
    }

    public Item getItem(String name) {
        return require(name, Item.class);
    }

    public BlockPos getBlockPos(String name) {
        return require(name, BlockPos.class);
    }

    public UUID getUuid(String name) {
        return require(name, UUID.class);
    }

    public List<ItemQuantity> getItemQuantities(String name) {
        List<?> list = require(name, List.class);
        for (Object element : list) {
            if (!(element instanceof ItemQuantity)) {
                throw new IllegalStateException("Argument " + name + " holds a "
                        + element.getClass().getSimpleName() + ", not an ItemQuantity");
            }
        }
        @SuppressWarnings("unchecked")
        List<ItemQuantity> quantities = (List<ItemQuantity>) list;
        return quantities;
    }

    private <T> T require(String name, Class<T> type) {
        Object value = values.get(name);
        if (value == null) {
            throw new IllegalStateException("Argument " + name + " was not validated into this call");
        }
        if (!type.isInstance(value)) {
            throw new IllegalStateException("Argument " + name + " is a "
                    + value.getClass().getSimpleName() + ", not a " + type.getSimpleName());
        }
        return type.cast(value);
    }
}
