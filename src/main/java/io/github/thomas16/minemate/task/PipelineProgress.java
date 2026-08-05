package io.github.thomas16.minemate.task;

import com.player2.playerengine.tasks.crafting.CraftMacroPhase;
import com.player2.playerengine.tasks.crafting.CraftMacroPlan;
import com.player2.playerengine.tasks.crafting.CraftMacroPlanner;
import com.player2.playerengine.tasks.crafting.CraftMacroResourceTask;
import com.player2.playerengine.util.ItemTarget;

import java.util.List;

/**
 * Per-goal ingredient and sub-goal progress for composite acquisition (fetch, craft). The
 * supervisor's only liveness signal is a monotone {@code advance} double; counting the final
 * item alone leaves an entire mine-and-smelt pipeline invisible, so this folds in a fraction
 * built from ingredient high-water marks and the craft macro's phase. Both terms are monotone
 * (high-water) and the sum stays below one, so it can be added to any integer-stepped base
 * without reordering it.
 */
final class PipelineProgress {
    private final ItemTarget target;
    private List<ItemTarget> manifest = List.of();
    private int[] highWater = new int[0];
    private double phaseHighWater;

    PipelineProgress(ItemTarget target) {
        this.target = target;
    }

    void onStart(GoalProbe.Context ctx) {
        try {
            manifest = CraftMacroPlanner.plan(ctx.controller(), target)
                    .filter(plan -> !plan.isEmpty())
                    .map(CraftMacroPlan::externalMaterials)
                    .orElse(List.of());
        } catch (RuntimeException e) {
            manifest = List.of();
        }
        highWater = new int[manifest.size()];
        phaseHighWater = 0.0;
    }

    /** Monotone sub-goal progress in [0, 1). */
    double subAdvance(GoalProbe.Context ctx) {
        double ingredientFraction = 0.0;
        if (!manifest.isEmpty()) {
            double sum = 0.0;
            for (int i = 0; i < manifest.size(); i++) {
                ItemTarget material = manifest.get(i);
                int held;
                try {
                    held = ctx.controller().getItemStorage()
                            .getItemCountInventoryOnly(material.getMatches());
                } catch (RuntimeException e) {
                    held = 0;
                }
                highWater[i] = Math.max(highWater[i], Math.min(held, material.getTargetCount()));
                sum += material.getTargetCount() <= 0
                        ? 1.0 : (double) highWater[i] / material.getTargetCount();
            }
            ingredientFraction = sum / manifest.size();
        }
        phaseHighWater = Math.max(phaseHighWater, macroPhaseFraction(ctx));
        return 0.9 * ingredientFraction + 0.09 * phaseHighWater;
    }

    String phaseWord(GoalProbe.Context ctx) {
        return TaskPhases.phase(ctx.task());
    }

    /** Coarse summary like {@code "materials 2/3"}, or null when no ingredient manifest exists. */
    String materialsSummary() {
        if (manifest.isEmpty()) {
            return null;
        }
        int met = 0;
        for (int i = 0; i < manifest.size(); i++) {
            if (highWater[i] >= manifest.get(i).getTargetCount()) {
                met++;
            }
        }
        return "materials " + met + "/" + manifest.size();
    }

    private static double macroPhaseFraction(GoalProbe.Context ctx) {
        if (ctx.task() == null) {
            return 0.0;
        }
        CraftMacroPhase[] found = new CraftMacroPhase[1];
        try {
            ctx.task().thisOrChildSatisfies(task -> {
                if (task instanceof CraftMacroResourceTask macro) {
                    found[0] = macro.getPhase();
                }
                return found[0] != null;
            });
        } catch (RuntimeException e) {
            return 0.0;
        }
        if (found[0] == null) {
            return 0.0;
        }
        return (double) found[0].ordinal() / (CraftMacroPhase.values().length - 1);
    }
}
