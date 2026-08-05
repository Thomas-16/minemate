package io.github.thomas16.minemate.task;

import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.containeraccess.ScanReportFormatter.TransferDirection;
import com.player2.playerengine.containeraccess.StorageAccessCode;
import com.player2.playerengine.containeraccess.StorageItemArgs.ItemQuery;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.container.BoundedContainerTransferTask;
import com.player2.playerengine.tasks.cooking.SmeltDeferredParams;
import com.player2.playerengine.tasks.cooking.SmeltDeferredTask;
import com.player2.playerengine.tasks.cooking.SmeltDeferredTask.OutcomeKind;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccess;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccess.CookKind;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccess.CookResolution;
import com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccessImpl;
import com.player2.playerengine.tasks.crafting.CraftMacroTasks;
import com.player2.playerengine.util.ItemTarget;

import io.github.thomas16.minemate.entity.CompanionEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;

/** MineMate-owned contracts and verifiers for the PlayerEngine capabilities certified in stage 3. */
final class CertifiedGoals {
    private static final CookingRecipeAccess COOKING_RECIPES = new CookingRecipeAccessImpl();
    private static final int STORAGE_TIMEOUT_TICKS = 1200;

    private CertifiedGoals() {
    }

    static Goal craft(CompanionEntity companion, Item output, int desiredTotal) {
        requireCount(desiredTotal);
        Objects.requireNonNull(companion, "companion");
        Objects.requireNonNull(output, "output");
        String outputId = itemId(output);
        GoalProbe probe = new CraftPipelineProbe(output, desiredTotal, outputId);
        return new Goal(Goal.nextId(), "craft", "craft " + desiredTotal + " " + outputId,
                GoalExecutionProfile.composite(
                        "May acquire recipe ingredients and craft or use a crafting table"),
                () -> {
                    if (inventoryCount(companion, output) >= desiredTotal) {
                        return new AlreadySatisfiedTask();
                    }
                    ItemTarget target = new ItemTarget(output, desiredTotal);
                    CraftMacroTasks.MacroProbe macro = CraftMacroTasks.macroOutcome(
                            companion.getController(), target);
                    if (macro.outcome() != CraftMacroTasks.MacroOutcome.MACRO_CREATED
                            || macro.taskOrNull() == null) {
                        throw new GoalStartException(TaskTerminalResult.blocked(Reasons.NO_CRAFTING_RECIPE));
                    }
                    // First attempt reuses the macro created above; retries re-plan against the
                    // inventory as it stands, where a null re-plan is terminal.
                    Task[] first = { macro.taskOrNull() };
                    return new ResilientAcquisitionTask(target, () -> {
                        if (first[0] != null) {
                            Task initial = first[0];
                            first[0] = null;
                            return initial;
                        }
                        CraftMacroTasks.MacroProbe replan = CraftMacroTasks.macroOutcome(
                                companion.getController(), target);
                        return replan.outcome() == CraftMacroTasks.MacroOutcome.MACRO_CREATED
                                ? replan.taskOrNull() : null;
                    }, outputId);
                }, probe, 6000, 0);
    }

    static Goal smelt(CompanionEntity companion, Item input, int desiredOutputTotal) {
        requireCount(desiredOutputTotal);
        Objects.requireNonNull(companion, "companion");
        Objects.requireNonNull(input, "input");
        ServerLevel level = serverLevel(companion);
        CookResolution resolution = COOKING_RECIPES.resolve(level.getRecipeManager(), input,
                CookKind.SMELTING, level.registryAccess()).orElse(null);
        String inputId = itemId(input);
        if (resolution == null || resolution.output().isEmpty()) {
            return unavailableSmeltGoal(inputId, desiredOutputTotal);
        }

        Item output = resolution.output().getItem();
        int yield = Math.max(1, resolution.output().getCount());
        int maximumInputs = divideRoundUp(desiredOutputTotal, yield);
        int nativeTimeoutSeconds = Math.max(90, maximumInputs * 12 + 60);
        GoalProbe probe = new ExactInventoryProbe(output, desiredOutputTotal,
                "smelting " + inputId + " into " + itemId(output)) {
            @Override
            public TaskTerminalResult terminalResult(Context context) {
                if (!(context.task() instanceof SmeltDeferredTask task) || task.outcome() == null) {
                    return null;
                }
                OutcomeKind kind = task.outcome().kind();
                return switch (kind) {
                    case CLEAN_SUCCESS -> null;
                    case PARTIAL_OUT_OF_FUEL -> TaskTerminalResult.blocked(Reasons.PARTIAL_OUT_OF_FUEL);
                    case NO_FUEL -> TaskTerminalResult.blocked(Reasons.NO_FUEL);
                    case TAMPERED -> TaskTerminalResult.failed(Reasons.FURNACE_TAMPERED);
                    case FURNACE_GONE -> TaskTerminalResult.blocked(Reasons.FURNACE_GONE);
                    case STALLED_RETRYING -> task.stopped() || task.isFinished()
                            ? TaskTerminalResult.blocked(Reasons.SMELT_STALLED) : null;
                    case STALLED_TIMEOUT -> TaskTerminalResult.timedOut(Reasons.SMELT_STALLED);
                    case NO_RECIPE -> TaskTerminalResult.blocked(Reasons.NO_SMELTING_RECIPE);
                    case FURNACE_IN_USE -> TaskTerminalResult.blocked(Reasons.FURNACE_IN_USE);
                    case SETUP_FAILED -> TaskTerminalResult.blocked(Reasons.SETUP_FAILED);
                };
            }
        };
        return new Goal(Goal.nextId(), "smelt",
                "smelt " + inputId + " into " + itemId(output) + " until holding " + desiredOutputTotal,
                GoalExecutionProfile.direct(
                        "Uses held input and fuel in a reachable standard furnace; may leave partial output"),
                () -> {
                    int missingOutput = Math.max(0, desiredOutputTotal - inventoryCount(companion, output));
                    if (missingOutput == 0) {
                        return new AlreadySatisfiedTask();
                    }
                    int inputs = divideRoundUp(missingOutput, yield);
                    SmeltDeferredParams params = new SmeltDeferredParams.Builder(input, inputs)
                            .preferredKind(CookKind.SMELTING)
                            .timeoutSeconds(Math.max(90, inputs * 12 + 60))
                            .build();
                    return new SmeltDeferredTask(params);
                }, probe, nativeTimeoutSeconds * 20 + 200, 0);
    }

    static Goal deposit(CompanionEntity companion, BlockPos containerPos, Item item, int desiredTotal) {
        requireCount(desiredTotal);
        return storage(companion, containerPos, item, desiredTotal, TransferDirection.DEPOSIT);
    }

    static Goal withdraw(CompanionEntity companion, BlockPos containerPos, Item item, int desiredTotal) {
        requireCount(desiredTotal);
        return storage(companion, containerPos, item, desiredTotal, TransferDirection.WITHDRAW);
    }

    private static Goal storage(CompanionEntity companion, BlockPos pos, Item item, int desiredTotal,
            TransferDirection direction) {
        Objects.requireNonNull(companion, "companion");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(item, "item");
        ServerLevel level = serverLevel(companion);
        String itemId = itemId(item);
        String kind = direction == TransferDirection.DEPOSIT ? "deposit" : "withdraw";
        GoalProbe probe = new StorageProbe(level, pos, item, desiredTotal, direction);
        return new Goal(Goal.nextId(), kind,
                kind + " " + itemId + " until destination has " + desiredTotal + " at " + format(pos),
                GoalExecutionProfile.direct("Moves an exact item to or from one exact loaded container"),
                () -> {
                    ContainerResolver.Resolution resolved = ContainerResolver.resolve(level, pos);
                    if (!resolved.ok()) {
                        throw new GoalStartException(TaskTerminalResult.blocked(reason(resolved.code()), 0));
                    }
                    int current = direction == TransferDirection.DEPOSIT
                            ? containerCount(resolved.resolved().container(), item)
                            : inventoryCount(companion, item);
                    int missing = Math.max(0, desiredTotal - current);
                    if (missing == 0) {
                        return new AlreadySatisfiedTask();
                    }
                    if (direction == TransferDirection.WITHDRAW) {
                        return BoundedContainerTransferTask.withdrawUpTo(pos, item, missing);
                    }
                    ItemQuery query = new ItemQuery(item, itemId, missing);
                    return new BoundedContainerTransferTask(direction, pos, List.of(query));
                }, probe, STORAGE_TIMEOUT_TICKS, 300);
    }

    private static Goal unavailableSmeltGoal(String inputId, int desiredTotal) {
        return new Goal(Goal.nextId(), "smelt", "smelt " + inputId + " until holding " + desiredTotal,
                GoalExecutionProfile.direct("Requires a standard furnace recipe"),
                () -> {
                    throw new GoalStartException(TaskTerminalResult.blocked(Reasons.NO_SMELTING_RECIPE));
                }, context -> GoalProgress.counted(0, desiredTotal,
                        "No standard smelting recipe for " + inputId), 1, 0);
    }

    private static int inventoryCount(CompanionEntity companion, Item item) {
        return containerCount(companion.getLivingInventory(), item);
    }

    private static int containerCount(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static String format(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static int divideRoundUp(int numerator, int denominator) {
        return (numerator + denominator - 1) / denominator;
    }

    private static void requireCount(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive");
        }
    }

    private static ServerLevel serverLevel(CompanionEntity companion) {
        if (!(companion.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("Certified goals require a server world");
        }
        return level;
    }

    private static String reason(StorageAccessCode code) {
        return code == null || code == StorageAccessCode.OK ? Reasons.ENGINE_BLOCKED : code.token();
    }

    /** Ingredient-aware probe for the craft pipeline; see {@link PipelineProgress}. */
    private static final class CraftPipelineProbe implements GoalProbe {
        private final Item output;
        private final int desiredTotal;
        private final String activity;
        private final PipelineProgress pipeline;

        private CraftPipelineProbe(Item output, int desiredTotal, String outputId) {
            this.output = output;
            this.desiredTotal = desiredTotal;
            this.activity = "crafting " + outputId;
            this.pipeline = new PipelineProgress(new ItemTarget(output, desiredTotal));
        }

        @Override
        public void onStart(Context context) {
            pipeline.onStart(context);
        }

        @Override
        public GoalProgress sample(Context context) {
            int have = inventoryCount(context.companion(), output);
            if (!(context.task() instanceof ResilientAcquisitionTask)) {
                return GoalProgress.counted(have, desiredTotal,
                        activity + ": " + have + "/" + desiredTotal);
            }
            String materials = pipeline.materialsSummary();
            String text = activity + ": " + have + "/" + desiredTotal
                    + " - " + pipeline.phaseWord(context)
                    + (materials == null ? "" : " (" + materials + ")");
            return new GoalProgress(have, desiredTotal, have + pipeline.subAdvance(context), text);
        }

        @Override
        public TaskTerminalResult terminalResult(Context context) {
            if (context.task() instanceof ResilientAcquisitionTask wrapper
                    && wrapper.terminalFailure().isPresent()) {
                return TaskTerminalResult.blocked(Reasons.MATERIALS_UNAVAILABLE);
            }
            return null;
        }
    }

    private abstract static class ExactInventoryProbe implements GoalProbe {
        private final Item item;
        private final int desiredTotal;
        private final String activity;

        private ExactInventoryProbe(Item item, int desiredTotal, String activity) {
            this.item = item;
            this.desiredTotal = desiredTotal;
            this.activity = activity;
        }

        @Override
        public GoalProgress sample(Context context) {
            int have = inventoryCount(context.companion(), item);
            return GoalProgress.counted(have, desiredTotal,
                    activity + ": " + have + "/" + desiredTotal);
        }
    }

    private static final class StorageProbe implements GoalProbe {
        private final ServerLevel level;
        private final BlockPos pos;
        private final Item item;
        private final int desiredTotal;
        private final TransferDirection direction;

        private StorageProbe(ServerLevel level, BlockPos pos, Item item, int desiredTotal,
                TransferDirection direction) {
            this.level = level;
            this.pos = pos;
            this.item = item;
            this.desiredTotal = desiredTotal;
            this.direction = direction;
        }

        @Override
        public GoalProgress sample(Context context) {
            int have;
            if (direction == TransferDirection.WITHDRAW) {
                have = inventoryCount(context.companion(), item);
            } else {
                ContainerResolver.Resolution resolved = ContainerResolver.resolve(level, pos);
                have = resolved.ok() ? containerCount(resolved.resolved().container(), item) : 0;
            }
            return GoalProgress.counted(have, desiredTotal,
                    direction.name().toLowerCase() + " " + itemId(item) + ": "
                            + have + "/" + desiredTotal);
        }

        @Override
        public TaskTerminalResult terminalResult(Context context) {
            if (!(context.task() instanceof BoundedContainerTransferTask task) || task.outcome() == null
                    || task.outcome() == BoundedContainerTransferTask.Outcome.MOVED_ALL) {
                return null;
            }
            int moved = task.entryOutcomes().stream().mapToInt(entry -> entry.moved()).sum();
            if (task.code() != null && task.code() != StorageAccessCode.OK) {
                return TaskTerminalResult.blocked(reason(task.code()), moved);
            }
            if (!task.failures().isEmpty()) {
                return TaskTerminalResult.blocked(reason(task.failures().get(0).code()), moved);
            }
            return TaskTerminalResult.blocked(direction == TransferDirection.DEPOSIT
                    ? Reasons.INSUFFICIENT_SPACE : Reasons.INSUFFICIENT_ITEMS, moved);
        }
    }

    /** Harmless placeholder cancelled immediately when an independent verifier is already complete. */
    private static final class AlreadySatisfiedTask extends Task {
        private boolean done;

        @Override
        protected void onStart() {
            done = false;
        }

        @Override
        protected Task onTick() {
            done = true;
            return null;
        }

        @Override
        protected void onStop(Task interruptTask) {
        }

        @Override
        public boolean isFinished() {
            return done;
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof AlreadySatisfiedTask;
        }

        @Override
        protected String toDebugString() {
            return "Already satisfied";
        }
    }
}
