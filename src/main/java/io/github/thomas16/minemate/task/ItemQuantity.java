package io.github.thomas16.minemate.task;

import java.util.Objects;

/** One requested deliverable: a PlayerEngine {@code TaskCatalogue} resource name and a positive count. */
public record ItemQuantity(String item, int count) {

    public ItemQuantity {
        Objects.requireNonNull(item, "item");
        if (item.isBlank()) {
            throw new IllegalArgumentException("Item name must not be blank");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive");
        }
    }
}
