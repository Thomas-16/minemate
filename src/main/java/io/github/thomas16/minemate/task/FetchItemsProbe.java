package io.github.thomas16.minemate.task;

import com.player2.playerengine.util.ItemTarget;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies a multi-item delivery ({@code fetch_items}). Progress counts whole entries delivered
 * to the owner, not item units, so one large stack cannot drown the other deliverables; the
 * advance is a per-entry latched score, which keeps it monotone across item transitions even
 * though the active entry's {@link PipelineProgress} starts over per item.
 *
 * <p>Known limitation: two entries whose catalogue match sets overlap (such as {@code planks}
 * and {@code oak_planks}) can credit one delivery to both entries. Harmless for the tool-set
 * shape this exists for; do not feed it deliberately aliased entries.
 */
final class FetchItemsProbe implements GoalProbe, DeliveryEvidenceSource {

    private final List<OwnerGiveItemsTask.FetchEntry> entries;
    private final int[] ownerBaseline;
    private final int[] maxOwner;
    private final int[] maxHeld;
    private final double[] score;
    private PipelineProgress pipeline;
    private int pipelineIndex = -1;

    FetchItemsProbe(List<OwnerGiveItemsTask.FetchEntry> entries) {
        this.entries = List.copyOf(entries);
        this.ownerBaseline = new int[this.entries.size()];
        this.maxOwner = new int[this.entries.size()];
        this.maxHeld = new int[this.entries.size()];
        this.score = new double[this.entries.size()];
    }

    @Override
    public void onStart(Context context) {
        ServerPlayer owner = Goals.owner(context);
        for (int i = 0; i < entries.size(); i++) {
            OwnerGiveItemsTask.FetchEntry entry = entries.get(i);
            ownerBaseline[i] = owner == null ? 0 : Goals.playerInventoryCount(owner, entry.matches());
            maxOwner[i] = ownerBaseline[i];
            maxHeld[i] = Goals.inventoryCount(context, entry.matches());
            score[i] = 0.0;
        }
        pipeline = null;
        pipelineIndex = -1;
    }

    @Override
    public GoalProgress sample(Context context) {
        ServerPlayer owner = Goals.owner(context);
        int active = context.task() instanceof OwnerGiveItemsTask task ? task.currentIndex() : 0;
        if (pipelineIndex != active) {
            // A fresh pipeline per active entry; earlier entries' contributions stay latched.
            OwnerGiveItemsTask.FetchEntry entry = entries.get(active);
            pipeline = new PipelineProgress(new ItemTarget(entry.item(), entry.count()));
            pipeline.onStart(context);
            pipelineIndex = active;
        }
        int deliveredCount = 0;
        double advance = 0.0;
        for (int i = 0; i < entries.size(); i++) {
            OwnerGiveItemsTask.FetchEntry entry = entries.get(i);
            maxHeld[i] = Math.max(maxHeld[i], Goals.inventoryCount(context, entry.matches()));
            if (owner != null) {
                maxOwner[i] = Math.max(maxOwner[i], Goals.playerInventoryCount(owner, entry.matches()));
            }
            int deliveredUnits = deliveredUnits(i);
            double computed;
            if (deliveredUnits >= entry.count()) {
                deliveredCount++;
                computed = 1.0;
            } else {
                double base = 0.5 * deliveredUnits + 0.5 * Math.min(maxHeld[i], entry.count());
                computed = 0.9 * (base / entry.count());
                if (i == active) {
                    computed += 0.09 * pipeline.subAdvance(context);
                }
            }
            score[i] = Math.max(score[i], computed);
            advance += score[i];
        }
        String text;
        if (deliveredCount >= entries.size()) {
            text = "%d/%d items delivered".formatted(deliveredCount, entries.size());
        } else {
            String materials = pipeline.materialsSummary();
            text = "%d/%d items - %s: %s".formatted(deliveredCount, entries.size(),
                    entries.get(active).item(), pipeline.phaseWord(context))
                    + (materials == null ? "" : " (" + materials + ")");
        }
        return new GoalProgress(deliveredCount, entries.size(), advance, text);
    }

    @Override
    public TaskTerminalResult terminalResult(Context context) {
        if (!(context.task() instanceof OwnerGiveItemsTask task)
                || !task.processedAll() || !task.anySkipped()) {
            return null;
        }
        List<String> delivered = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int deliveredUnitsTotal = 0;
        for (int i = 0; i < entries.size(); i++) {
            int units = deliveredUnits(i);
            deliveredUnitsTotal += Math.min(units, entries.get(i).count());
            if (units >= entries.get(i).count()) {
                delivered.add(entries.get(i).item());
            } else {
                missing.add(entries.get(i).item());
            }
        }
        if (missing.isEmpty()) {
            // Everything reached the owner after all; let the completion re-sample succeed.
            return null;
        }
        String detail = "Delivered "
                + (delivered.isEmpty() ? "nothing" : String.join(", ", delivered))
                + "; could not get " + String.join(", ", missing);
        return TaskTerminalResult.blocked(Reasons.RESOURCE_UNAVAILABLE, deliveredUnitsTotal, detail);
    }

    @Override
    public String blockedReason(Context context) {
        // Only a missing owner blocks mid-run; a single entry's exhaustion is skipped by the
        // task and reported through terminalResult once the remaining entries have been tried.
        return Goals.owner(context) == null ? Reasons.OWNER_OFFLINE : null;
    }

    @Override
    public List<DeliveryEvidence> deliveryEvidence() {
        List<DeliveryEvidence> evidence = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            evidence.add(new DeliveryEvidence(entries.get(i).item(), entries.get(i).count(),
                    deliveredUnits(i)));
        }
        return evidence;
    }

    private int deliveredUnits(int index) {
        return Math.min(entries.get(index).count(), Math.max(0, maxOwner[index] - ownerBaseline[index]));
    }
}
