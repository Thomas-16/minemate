package io.github.thomas16.minemate.task;

/**
 * Units of one item confirmed in the owner's inventory beyond its pre-goal baseline. The measured
 * fact behind a delivery receipt: {@code delivered} comes from an owner-inventory delta, never
 * from a task having thrown the items.
 */
public record DeliveryEvidence(String item, int requested, int delivered) {
}
