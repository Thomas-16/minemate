package io.github.thomas16.minemate.task;

import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.tasks.crafting.CraftMacroTasks;
import com.player2.playerengine.tasks.entity.GiveItemToPlayerTask;
import com.player2.playerengine.tasks.entity.KillEntityTask;
import com.player2.playerengine.tasks.movement.FollowPlayerTask;
import com.player2.playerengine.tasks.movement.GetToXZTask;
import com.player2.playerengine.util.ItemTarget;
import com.player2.playerengine.util.helpers.StorageHelper;

import io.github.thomas16.minemate.entity.CompanionEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.UUID;

/** Factory methods for the hardcoded stage-2 goal vocabulary. */
public final class Goals {
    private static final int COLLECT_TIMEOUT_TICKS = 6000;
    private static final int COLLECT_STALL_TICKS = 1200;

    private Goals() {
    }

    public static Goal mine(String resource, int count) {
        return collect("mine", resource, count);
    }

    public static Goal fetch(String resource, int count) {
        return collect("fetch", resource, count);
    }

    public static Goal craft(CompanionEntity companion, Item output, int desiredTotal) {
        return CertifiedGoals.craft(companion, output, desiredTotal);
    }

    public static Goal smelt(CompanionEntity companion, Item input, int desiredOutputTotal) {
        return CertifiedGoals.smelt(companion, input, desiredOutputTotal);
    }

    public static Goal deposit(CompanionEntity companion, BlockPos containerPos, Item item,
            int desiredContainerTotal) {
        return CertifiedGoals.deposit(companion, containerPos, item, desiredContainerTotal);
    }

    public static Goal withdraw(CompanionEntity companion, BlockPos containerPos, Item item,
            int desiredInventoryTotal) {
        return CertifiedGoals.withdraw(companion, containerPos, item, desiredInventoryTotal);
    }

    public static Goal goTo(int x, int z, double arriveRadius) {
        if (arriveRadius <= 0.0) {
            throw new IllegalArgumentException("Arrival radius must be positive");
        }
        String description = "go to " + x + ", " + z;
        GoalProbe probe = context -> {
            double dx = context.companion().getX() - (x + 0.5);
            double dz = context.companion().getZ() - (z + 0.5);
            double distance = Math.sqrt(dx * dx + dz * dz);
            return GoalProgress.approach(distance, arriveRadius,
                    "%.1f blocks from %d, %d".formatted(distance, x, z));
        };
        return new Goal(Goal.nextId(), "goto", description,
                GoalExecutionProfile.direct("May break or place route-support blocks while pathfinding"),
                () -> new GetToXZTask(x, z),
                probe, 2400, 400);
    }

    public static Goal killEntity(ServerLevel level, UUID targetUuid, String targetName) {
        String description = "kill " + targetName;
        GoalProbe probe = new GoalProbe() {
            @Override
            public GoalProgress sample(Context context) {
                Entity target = level.getEntity(targetUuid);
                if (target == null || target.isRemoved() || !target.isAlive()) {
                    return GoalProgress.counted(1, 1, targetName + " defeated");
                }
                double advance;
                String text;
                if (target instanceof LivingEntity living) {
                    advance = living.getMaxHealth() - living.getHealth();
                    text = "%s: %.1f/%.1f health".formatted(
                            targetName, living.getHealth(), living.getMaxHealth());
                } else {
                    double distance = context.companion().distanceTo(target);
                    advance = -distance;
                    text = "%.1f blocks from %s".formatted(distance, targetName);
                }
                return new GoalProgress(0, 1, advance, text);
            }
        };
        return new Goal(Goal.nextId(), "kill", description,
                GoalExecutionProfile.direct("Pursues and attacks the selected entity"), () -> {
            Entity target = level.getEntity(targetUuid);
            if (target == null || target.isRemoved() || !target.isAlive()) {
                throw new IllegalStateException(Reasons.TARGET_GONE);
            }
            return new KillEntityTask(target);
        }, probe, 1200, 400);
    }

    public static Goal follow(ServerPlayer player, double distance) {
        if (distance < 0.0) {
            throw new IllegalArgumentException("Follow distance cannot be negative");
        }
        UUID playerUuid = player.getUUID();
        String playerName = player.getGameProfile().getName();
        GoalProbe probe = new GoalProbe() {
            @Override
            public GoalProgress sample(Context context) {
                ServerPlayer current = context.companion().getServer().getPlayerList().getPlayer(playerUuid);
                if (current == null) {
                    return GoalProgress.openEnded("Waiting for " + playerName);
                }
                return GoalProgress.openEnded("Following %s (%.1f blocks away)".formatted(
                        playerName, context.companion().distanceTo(current)));
            }

            @Override
            public String blockedReason(Context context) {
                return context.companion().getServer().getPlayerList().getPlayer(playerUuid) == null
                        ? Reasons.OWNER_OFFLINE : null;
            }
        };
        return new Goal(Goal.nextId(), "follow", "follow " + playerName,
                GoalExecutionProfile.continuous("Continuously pathfinds toward the selected player"),
                () -> new FollowPlayerTask(playerName, distance), probe, 0, 0);
    }

    private static Goal collect(String kind, String resource, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive");
        }
        if (!TaskCatalogue.taskExists(resource)) {
            throw new IllegalArgumentException(Reasons.UNKNOWN_RESOURCE + ": " + resource);
        }
        Item[] matches = TaskCatalogue.getItemMatches(resource);
        String description = kind + " " + count + " " + resource;
        if ("fetch".equals(kind)) {
            return new Goal(Goal.nextId(), kind, description,
                    GoalExecutionProfile.composite(
                            "May gather ingredients, craft tools, smelt, fight, and travel before delivery"),
                    () -> new OwnerGiveItemTask(resource, count, matches),
                    new FetchProbe(matches, resource, count),
                    COLLECT_TIMEOUT_TICKS, 0);
        }
        return new Goal(Goal.nextId(), kind, description,
                GoalExecutionProfile.composite(
                        "May gather ingredients, craft tools, smelt, fight, and travel to obtain the item"),
                () -> TaskCatalogue.getItemTask(resource, count),
                new CollectProbe(matches, resource, count),
                COLLECT_TIMEOUT_TICKS, COLLECT_STALL_TICKS);
    }

    private static int inventoryCount(GoalProbe.Context context, Item[] matches) {
        return context.controller().getItemStorage().getItemCountInventoryOnly(matches);
    }

    private static ServerPlayer owner(GoalProbe.Context context) {
        return owner(context.companion());
    }

    private static ServerPlayer owner(CompanionEntity companion) {
        return companion.resolveOwner().orElse(null);
    }

    private static int playerInventoryCount(ServerPlayer player, Item[] matches) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            for (Item match : matches) {
                if (stack.is(match)) {
                    count += stack.getCount();
                    break;
                }
            }
        }
        return count;
    }

    private static final class CollectProbe implements GoalProbe {
        private final Item[] matches;
        private final String resource;
        private final int count;
        private int baseline;

        private CollectProbe(Item[] matches, String resource, int count) {
            this.matches = matches;
            this.resource = resource;
            this.count = count;
        }

        @Override
        public void onStart(Context context) {
            baseline = inventoryCount(context, matches);
        }

        @Override
        public GoalProgress sample(Context context) {
            int have = inventoryCount(context, matches);
            return GoalProgress.counted(have, count,
                    "%d/%d %s (had %d to start)".formatted(have, count, resource, baseline));
        }

        @Override
        public String blockedReason(Context context) {
            if (context.task() instanceof ResourceTask resourceTask
                    && resourceTask.getAggregatedFailureReason().isPresent()) {
                return Reasons.RESOURCE_UNAVAILABLE;
            }
            return null;
        }
    }

    private static final class FetchProbe implements GoalProbe {
        private final Item[] matches;
        private final String resource;
        private final int count;
        private int companionBaseline;
        private int ownerBaseline;
        private int maxHeld;
        private int maxOwnerCount;

        private FetchProbe(Item[] matches, String resource, int count) {
            this.matches = matches;
            this.resource = resource;
            this.count = count;
        }

        @Override
        public void onStart(Context context) {
            companionBaseline = inventoryCount(context, matches);
            maxHeld = companionBaseline;
            ServerPlayer currentOwner = owner(context);
            ownerBaseline = currentOwner == null ? 0 : playerInventoryCount(currentOwner, matches);
            maxOwnerCount = ownerBaseline;
        }

        @Override
        public GoalProgress sample(Context context) {
            int held = inventoryCount(context, matches);
            maxHeld = Math.max(maxHeld, held);
            ServerPlayer currentOwner = owner(context);
            if (currentOwner != null) {
                maxOwnerCount = Math.max(maxOwnerCount, playerInventoryCount(currentOwner, matches));
            }
            int delivered = Math.min(count, Math.max(0, maxOwnerCount - ownerBaseline));
            double advance = delivered > 0
                    ? count + delivered
                    : Math.min(maxHeld, count);
            String phase = delivered >= count ? "delivered" : delivered > 0 ? "delivering" : "collecting";
            return new GoalProgress(delivered, count, advance,
                    "%d/%d %s %s (companion had %d; owner had %d)".formatted(
                            delivered, count, resource, phase, companionBaseline, ownerBaseline));
        }

        @Override
        public String blockedReason(Context context) {
            if (owner(context) == null) {
                return Reasons.OWNER_OFFLINE;
            }
            if (context.task() instanceof OwnerGiveItemTask fetchTask
                    && fetchTask.resourceFailure().isPresent()) {
                return Reasons.RESOURCE_UNAVAILABLE;
            }
            return null;
        }
    }

    /** Resolves the persisted MineMate owner only after PlayerEngine assigns the task. */
    private static final class OwnerGiveItemTask extends Task {
        private final String resource;
        private final int count;
        private final Item[] matches;
        private ItemTarget target;
        private Task acquisition;
        private GiveItemToPlayerTask delivery;
        private String ownerName;
        private String acquisitionFailure;
        private int ownerBaseline;
        private boolean acquisitionComplete;
        private boolean done;

        private OwnerGiveItemTask(String resource, int count, Item[] matches) {
            this.resource = resource;
            this.count = count;
            this.matches = matches.clone();
        }

        @Override
        protected void onStart() {
            done = false;
            acquisitionComplete = false;
            acquisitionFailure = null;
            acquisition = null;
            delivery = null;
            target = null;
            ownerName = null;
            if (!(controller.getEntity() instanceof CompanionEntity companion)) {
                fail(Reasons.OWNER_OFFLINE);
                return;
            }
            ServerPlayer owner = Goals.owner(companion);
            if (owner == null) {
                fail(Reasons.OWNER_OFFLINE);
                return;
            }
            ownerName = owner.getGameProfile().getName();
            ownerBaseline = playerInventoryCount(owner, matches);
            target = new ItemTarget(resource, count);
            acquisitionComplete = StorageHelper.itemTargetsMet(controller, target);
            if (!acquisitionComplete) {
                acquisition = createAcquisitionTask();
            }
        }

        @Override
        protected Task onTick() {
            if (target == null) {
                return null;
            }
            if (!acquisitionComplete) {
                if (StorageHelper.itemTargetsMet(controller, target)) {
                    acquisitionComplete = true;
                } else {
                    if (acquisition == null) {
                        acquisition = createAcquisitionTask();
                    }
                    if (acquisition.stopped() || acquisition.isFinished()) {
                        acquisitionFailure = resourceFailureInTree().orElse(Reasons.RESOURCE_UNAVAILABLE);
                        fail(acquisitionFailure);
                        return null;
                    }
                    setDebugState("Acquiring " + resource + " for owner");
                    return acquisition;
                }
            }
            if (delivery == null) {
                delivery = new GiveItemToPlayerTask(ownerName, target);
            }
            if (ownerReceivedItems()) {
                done = true;
                stop();
                return null;
            }
            if (delivery.stopped() || delivery.isFinished()) {
                setDebugState("Waiting for owner to pick up " + resource);
                return null;
            }
            setDebugState("Delivering " + resource + " to owner");
            return delivery;
        }

        private boolean ownerReceivedItems() {
            if (!(controller.getEntity() instanceof CompanionEntity companion)) {
                return false;
            }
            ServerPlayer owner = Goals.owner(companion);
            return owner != null && playerInventoryCount(owner, matches) >= ownerBaseline + count;
        }

        private Task createAcquisitionTask() {
            CraftMacroTasks.MacroProbe macro = CraftMacroTasks.macroOutcome(controller, target);
            if (macro.outcome() == CraftMacroTasks.MacroOutcome.MACRO_CREATED
                    && macro.taskOrNull() != null) {
                return macro.taskOrNull();
            }
            return TaskCatalogue.getItemTask(resource, count);
        }

        @Override
        public boolean isFinished() {
            return done;
        }

        @Override
        protected void onStop(Task interruptTask) {
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof OwnerGiveItemTask fetch
                    && resource.equals(fetch.resource) && count == fetch.count;
        }

        @Override
        protected String toDebugString() {
            return "Fetch %d %s for owner".formatted(count, resource);
        }

        private Optional<String> resourceFailure() {
            if (acquisitionFailure != null) {
                return Optional.of(acquisitionFailure);
            }
            if (!acquisitionComplete) {
                return resourceFailureInTree();
            }
            return Optional.empty();
        }

        private Optional<String> resourceFailureInTree() {
            String[] reason = new String[1];
            thisOrChildSatisfies(task -> {
                if (task instanceof ResourceTask resourceTask) {
                    reason[0] = resourceTask.getAggregatedFailureReason().orElse(null);
                }
                return reason[0] != null;
            });
            return Optional.ofNullable(reason[0]);
        }
    }
}
