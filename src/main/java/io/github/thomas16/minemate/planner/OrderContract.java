package io.github.thomas16.minemate.planner;

import java.util.List;
import java.util.Locale;

/**
 * What an order requires, as declared by the planner itself: the final items the owner asked to
 * receive and whether they must be delivered. Deterministic code validates the accompanying plan
 * against this declaration, so a plan that promises delivery in speech but never delivers is caught
 * before it executes.
 *
 * <p>Immutable on purpose: it is carried inside a {@link Plan} across the planner's completion
 * thread onto the server thread.
 */
public record OrderContract(List<Deliverable> deliverables, boolean deliverToOwner) {

    public record Deliverable(String item, int count) {

        public Deliverable {
            item = OrderContract.normalize(item);
        }
    }

    public OrderContract {
        deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
    }

    /** "minecraft:iron_sword" and "iron_sword" name the same item. */
    public static String normalize(String item) {
        String id = item == null ? "" : item.strip().toLowerCase(Locale.ROOT);
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
