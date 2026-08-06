package io.github.thomas16.minemate.planner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Checks a proposed plan against the contract its own reply declared. Pure and deliberately
 * narrow: the only P0 rule with a deterministic answer is "declared deliverables for the owner
 * must be fetched", because {@code fetch} and {@code fetch_items} are the tools that acquire or
 * craft an item <em>and</em> deliver it. {@code craft} leaves the output with the companion,
 * which is exactly the playtest failure this exists to catch.
 */
public final class ContractCoverage {

    private ContractCoverage() {
    }

    /** Null when the steps cover the contract; otherwise one ASCII violation sentence. */
    public static String violation(OrderContract contract, List<PlanStep> steps) {
        if (contract == null || !contract.deliverToOwner() || contract.deliverables().isEmpty()) {
            return null;
        }
        // A plan already at the protocol bound may end at a useful checkpoint; the replan chain
        // finishes the coverage.
        if (steps.size() >= Plan.MAX_STEPS) {
            return null;
        }
        for (OrderContract.Deliverable deliverable : contract.deliverables()) {
            int fetched = 0;
            for (PlanStep step : steps) {
                JsonObject arguments = step.call().arguments();
                if ("fetch".equals(step.call().tool())) {
                    String resource = stringOf(arguments.get("resource"));
                    if (OrderContract.normalize(resource).equals(deliverable.item())) {
                        fetched += countOf(arguments.get("count"));
                    }
                } else if ("fetch_items".equals(step.call().tool())) {
                    fetched += itemsCountOf(arguments.get("items"), deliverable.item());
                }
            }
            if (fetched < deliverable.count()) {
                return "the plan does not fetch " + deliverable.count() + " " + deliverable.item()
                        + " for the owner; fetch acquires or crafts and then delivers";
            }
        }
        return null;
    }

    /**
     * Null when the steps cover every undelivered remainder in the ledger; otherwise one ASCII
     * violation sentence. Same {@code MAX_STEPS} checkpoint escape and raw-JSON counting as
     * {@link #violation}, but judged against measured delivery evidence instead of the full
     * contract, so a replan is only asked to fetch what the owner has not received yet.
     */
    public static String remainderViolation(OrderLedger ledger, List<PlanStep> steps) {
        if (ledger == null || steps.size() >= Plan.MAX_STEPS) {
            return null;
        }
        for (OrderLedger.Entry entry : ledger.entries()) {
            int remaining = entry.wanted() - entry.delivered();
            if (remaining <= 0) {
                continue;
            }
            int fetched = 0;
            for (PlanStep step : steps) {
                JsonObject arguments = step.call().arguments();
                if ("fetch".equals(step.call().tool())) {
                    String resource = stringOf(arguments.get("resource"));
                    if (OrderContract.normalize(resource).equals(entry.item())) {
                        fetched += countOf(arguments.get("count"));
                    }
                } else if ("fetch_items".equals(step.call().tool())) {
                    fetched += itemsCountOf(arguments.get("items"), entry.item());
                }
            }
            if (fetched < remaining) {
                return "the plan does not fetch the remaining " + remaining + " " + entry.item()
                        + " for the owner; the ledger shows " + Math.min(entry.delivered(),
                                entry.wanted()) + " of " + entry.wanted() + " delivered";
            }
        }
        return null;
    }

    private static String stringOf(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /**
     * Units of {@code item} covered by a raw fetch_items {@code items} argument. Same tolerance
     * as {@link #countOf}: entries that are not objects cover nothing, and if the array truly is
     * garbage the registry rejects the plan moments later anyway.
     */
    private static int itemsCountOf(JsonElement items, String item) {
        if (items == null || !items.isJsonArray()) {
            return 0;
        }
        int covered = 0;
        for (JsonElement element : items.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (OrderContract.normalize(stringOf(entry.get("item"))).equals(item)) {
                covered += countOf(entry.get("count"));
            }
        }
        return covered;
    }

    /**
     * Arguments are still raw wire JSON here, ahead of registry validation, so the count may be a
     * number, a numeric string, absent (the registry default of 1), or garbage. Garbage counts as
     * covering nothing; if it truly is garbage the registry rejects the plan moments later anyway.
     */
    private static int countOf(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return 1;
        }
        if (!value.isJsonPrimitive()) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(value.getAsString());
        } catch (NumberFormatException error) {
            return 0;
        }
    }
}
