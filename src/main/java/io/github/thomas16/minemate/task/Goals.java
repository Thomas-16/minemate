package io.github.thomas16.minemate.task;

import com.player2.playerengine.TaskCatalogue;
import com.player2.playerengine.tasks.ResourceTask;
import com.player2.playerengine.tasks.entity.KillEntityTask;
import com.player2.playerengine.tasks.movement.FollowPlayerTask;
import com.player2.playerengine.tasks.movement.GetToBlockTask;
import com.player2.playerengine.tasks.movement.GetToXZTask;
import com.player2.playerengine.util.ItemTarget;

import io.github.thomas16.minemate.entity.CompanionEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * One goal that delivers several final items to the owner in sequence, best effort: an entry
     * whose acquisition proves unavailable is skipped and reported in the outcome detail instead
     * of sinking the rest of the set.
     */
    public static Goal fetchItems(List<ItemQuantity> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Items must not be empty");
        }
        // Merge duplicates: two consecutive entries for the same item would build isEqual child
        // tasks, and PlayerEngine reuses an equal previous child instead of restarting it.
        LinkedHashMap<String, Integer> merged = new LinkedHashMap<>();
        for (ItemQuantity item : items) {
            if (!TaskCatalogue.taskExists(item.item())) {
                throw new IllegalArgumentException(Reasons.UNKNOWN_RESOURCE + ": " + item.item());
            }
            merged.merge(item.item(), item.count(), Integer::sum);
        }
        List<OwnerGiveItemsTask.FetchEntry> entries = new ArrayList<>(merged.size());
        StringBuilder description = new StringBuilder("fetch_items ");
        for (Map.Entry<String, Integer> item : merged.entrySet()) {
            entries.add(new OwnerGiveItemsTask.FetchEntry(item.getKey(), item.getValue(),
                    TaskCatalogue.getItemMatches(item.getKey())));
            if (entries.size() > 1) {
                description.append(", ");
            }
            description.append(item.getValue()).append(' ').append(item.getKey());
        }
        List<OwnerGiveItemsTask.FetchEntry> bound = List.copyOf(entries);
        return new Goal(Goal.nextId(), "fetch_items", description.toString(),
                GoalExecutionProfile.composite(
                        "May gather ingredients, craft tools, smelt, fight, and travel before each delivery"),
                () -> new OwnerGiveItemsTask(bound),
                new FetchItemsProbe(bound),
                // Each entry gets a standalone fetch's budget. The tool schema bounds a call to
                // 8 entries; note 6 or more would exceed the autotest harness's global tick cap.
                items.size() * COLLECT_TIMEOUT_TICKS, 0);
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

    public static Goal goTo(int x, int y, int z, double arriveRadius) {
        if (arriveRadius <= 0.0) {
            throw new IllegalArgumentException("Arrival radius must be positive");
        }
        String description = "go to " + x + ", " + y + ", " + z;
        GoalProbe probe = context -> {
            double dx = context.companion().getX() - (x + 0.5);
            // Feet position against the target's feet block, so standing on it reads as zero.
            double dy = context.companion().getY() - y;
            double dz = context.companion().getZ() - (z + 0.5);
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            return GoalProgress.approach(distance, arriveRadius,
                    "%.1f blocks from %d, %d, %d".formatted(distance, x, y, z));
        };
        return new Goal(Goal.nextId(), "goto", description,
                GoalExecutionProfile.direct("May break or place route-support blocks while pathfinding"),
                () -> new GetToBlockTask(new BlockPos(x, y, z)),
                probe, 2400, 400);
    }

    public static Goal comeToOwner(ServerPlayer owner, double arriveDistance) {
        if (arriveDistance <= 0.0) {
            throw new IllegalArgumentException("Arrival distance must be positive");
        }
        String ownerName = owner.getGameProfile().getName();
        GoalProbe probe = new GoalProbe() {
            @Override
            public GoalProgress sample(Context context) {
                ServerPlayer current = context.companion().resolveOwnerInLevel().orElse(null);
                if (current == null) {
                    return new GoalProgress(0, 1, 0.0, "Waiting for " + ownerName);
                }
                double distance = context.companion().distanceTo(current);
                return GoalProgress.approach(distance, arriveDistance,
                        "%.1f blocks from %s".formatted(distance, ownerName));
            }

            @Override
            public String blockedReason(Context context) {
                return context.companion().resolveOwnerInLevel().isEmpty()
                        ? Reasons.OWNER_OFFLINE : null;
            }
        };
        // stallTicks stays 0: advance is -distance to a target that may walk away, so any
        // stall watchdog would kill an actively chasing companion; the timeout is the bound.
        return new Goal(Goal.nextId(), "come_to_owner", "come to " + ownerName,
                GoalExecutionProfile.direct("May break or place route-support blocks while pathfinding"),
                () -> new ComeToOwnerTask(arriveDistance), probe, 2400, 0);
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

    static int inventoryCount(GoalProbe.Context context, Item[] matches) {
        return context.controller().getItemStorage().getItemCountInventoryOnly(matches);
    }

    static ServerPlayer owner(GoalProbe.Context context) {
        return owner(context.companion());
    }

    static ServerPlayer owner(CompanionEntity companion) {
        return companion.resolveOwner().orElse(null);
    }

    /** Owner inventory count for a catalogue item name, or -1 when the name is unknown. */
    public static int ownerCountOf(ServerPlayer owner, String item) {
        if (owner == null || !TaskCatalogue.taskExists(item)) {
            return -1;
        }
        return playerInventoryCount(owner, TaskCatalogue.getItemMatches(item));
    }

    static int playerInventoryCount(ServerPlayer player, Item[] matches) {
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
        /**
         * An engine failure reason must persist this long, with no new items arriving, before it
         * blocks the goal. Descendant ResourceTasks keep transient failure reasons while a parent
         * is still working, so acting on first sight kills live pipelines.
         */
        private static final int FAILURE_CONFIRM_TICKS = 100;

        private final Item[] matches;
        private final String resource;
        private final int count;
        private int baseline;
        private int lastSampledHave;
        private int failurePresentTicks;
        private int haveAtFailureStart;

        private CollectProbe(Item[] matches, String resource, int count) {
            this.matches = matches;
            this.resource = resource;
            this.count = count;
        }

        @Override
        public void onStart(Context context) {
            baseline = inventoryCount(context, matches);
            lastSampledHave = baseline;
        }

        @Override
        public GoalProgress sample(Context context) {
            int have = inventoryCount(context, matches);
            lastSampledHave = have;
            return GoalProgress.counted(have, count,
                    "%d/%d %s (had %d to start)".formatted(have, count, resource, baseline));
        }

        @Override
        public String blockedReason(Context context) {
            boolean failing = context.task() instanceof ResourceTask resourceTask
                    && resourceTask.getAggregatedFailureReason().isPresent();
            if (!failing) {
                failurePresentTicks = 0;
                return null;
            }
            if (failurePresentTicks == 0) {
                haveAtFailureStart = lastSampledHave;
            } else if (lastSampledHave > haveAtFailureStart) {
                failurePresentTicks = 0;
                return null;
            }
            failurePresentTicks++;
            return failurePresentTicks >= FAILURE_CONFIRM_TICKS ? Reasons.RESOURCE_UNAVAILABLE : null;
        }
    }

    private static final class FetchProbe implements GoalProbe, DeliveryEvidenceSource {
        private final Item[] matches;
        private final String resource;
        private final int count;
        private final PipelineProgress pipeline;
        private int companionBaseline;
        private int ownerBaseline;
        private int maxHeld;
        private int maxOwnerCount;

        private FetchProbe(Item[] matches, String resource, int count) {
            this.matches = matches;
            this.resource = resource;
            this.count = count;
            this.pipeline = new PipelineProgress(new ItemTarget(resource, count));
        }

        @Override
        public void onStart(Context context) {
            companionBaseline = inventoryCount(context, matches);
            maxHeld = companionBaseline;
            ServerPlayer currentOwner = owner(context);
            ownerBaseline = currentOwner == null ? 0 : playerInventoryCount(currentOwner, matches);
            maxOwnerCount = ownerBaseline;
            pipeline.onStart(context);
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
            double advance = (delivered > 0 ? count + delivered : Math.min(maxHeld, count))
                    + pipeline.subAdvance(context);
            String text;
            if (delivered >= count) {
                text = "%d/%d %s delivered".formatted(delivered, count, resource);
            } else if (delivered > 0) {
                text = "%d/%d %s delivering".formatted(delivered, count, resource);
            } else {
                String materials = pipeline.materialsSummary();
                text = "%d/%d %s - %s".formatted(delivered, count, resource,
                        pipeline.phaseWord(context))
                        + (materials == null ? "" : " (" + materials + ")");
            }
            return new GoalProgress(delivered, count, advance, text);
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

        @Override
        public List<DeliveryEvidence> deliveryEvidence() {
            return List.of(new DeliveryEvidence(resource, count,
                    Math.min(count, Math.max(0, maxOwnerCount - ownerBaseline))));
        }
    }

}
