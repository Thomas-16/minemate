package io.github.thomas16.minemate.context;

import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;

import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.integration.ReflexSnapshot;
import io.github.thomas16.minemate.task.Goal;
import io.github.thomas16.minemate.task.GoalProgress;
import io.github.thomas16.minemate.task.TaskActivitySnapshot;
import io.github.thomas16.minemate.task.TaskOutcome;
import io.github.thomas16.minemate.task.TaskSupervisor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a {@link WorldSnapshot} from the live server state.
 *
 * <p>Every read here is server-thread-only. Cost is bounded deliberately: the entity queries are
 * AABB lookups that touch only loaded chunks, line-of-sight is raycast for at most
 * {@value #MAX_THREATS} mobs, and nothing pathfinds. Even so this is not free, which is why
 * {@link CompanionContext#snapshot()} memoizes per game tick and nothing calls it every tick.
 */
public final class WorldSnapshots {

    private static final double THREAT_RADIUS = 24.0;
    private static final double ENTITY_RADIUS = 16.0;
    private static final int MAX_THREATS = 6;
    private static final int MAX_ENTITIES = 4;
    private static final int MAX_NOTABLE = 10;
    private static final int MAX_EVENTS = 8;
    private static final int HOTBAR_SLOTS = 9;

    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private WorldSnapshots() {
    }

    public static WorldSnapshot capture(CompanionEntity companion) {
        if (!(companion.level() instanceof ServerLevel level)) {
            throw new IllegalStateException("World snapshots are server-side only");
        }
        assert companion.getServer() != null && companion.getServer().isSameThread();

        long dayTime = level.getDayTime() % 24_000L;
        BlockPos pos = companion.blockPosition();

        return new WorldSnapshot(
                level.getGameTime(), dayTime, phaseOf(dayTime), level.isRaining(), level.isThundering(),
                level.dimension().location().getPath(), biomeOf(level, pos),
                selfOf(companion, level, pos), ownerOf(companion),
                threatsOf(companion, level), entitiesOf(companion, level),
                hotbarOf(companion), equipmentOf(companion), notableOf(companion),
                omittedKinds(companion),
                activityOf(companion), outcomeOf(companion), reflexOf(companion),
                eventsOf(companion));
    }

    private static WorldSnapshot.DayPhase phaseOf(long dayTime) {
        if (dayTime < 12_000L) {
            return WorldSnapshot.DayPhase.DAY;
        }
        if (dayTime < 13_800L) {
            return WorldSnapshot.DayPhase.SUNSET;
        }
        return dayTime < 22_200L ? WorldSnapshot.DayPhase.NIGHT : WorldSnapshot.DayPhase.SUNRISE;
    }

    private static String biomeOf(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(key -> key.location().getPath()).orElse("");
    }

    private static WorldSnapshot.SelfState selfOf(CompanionEntity companion, ServerLevel level, BlockPos pos) {
        int maxAir = Math.max(1, companion.getMaxAirSupply());
        return new WorldSnapshot.SelfState(
                pos.getX(), pos.getY(), pos.getZ(),
                companion.getX(), companion.getY(), companion.getZ(),
                companion.getHealth(), companion.getMaxHealth(),
                companion.getHungerManager().getFoodLevel(),
                companion.getHungerManager().getSaturationLevel(),
                companion.getArmorValue(),
                level.getMaxLocalRawBrightness(pos),
                companion.onGround(), companion.isInWater(), companion.isInLava(),
                companion.isOnFire(),
                Math.max(0, companion.getAirSupply()) * 100 / maxAir,
                itemName(companion.getLivingInventory().getMainHandStack()),
                blockName(level, pos.below()));
    }

    private static WorldSnapshot.OwnerState ownerOf(CompanionEntity companion) {
        ServerPlayer owner = companion.resolveOwner().orElse(null);
        if (owner == null) {
            return WorldSnapshot.OwnerState.absent();
        }
        boolean sameLevel = owner.level() == companion.level();
        if (!sameLevel) {
            return new WorldSnapshot.OwnerState(true, owner.getGameProfile().getName(), false,
                    -1.0, 0, 0, 0, "", owner.getHealth());
        }
        double dx = owner.getX() - companion.getX();
        double dy = owner.getY() - companion.getY();
        double dz = owner.getZ() - companion.getZ();
        return new WorldSnapshot.OwnerState(true, owner.getGameProfile().getName(), true,
                companion.distanceTo(owner), (int) Math.round(dx), (int) Math.round(dy),
                (int) Math.round(dz), compass(dx, dz), owner.getHealth());
    }

    private static List<WorldSnapshot.ThreatState> threatsOf(CompanionEntity companion, ServerLevel level) {
        AABB box = companion.getBoundingBox().inflate(THREAT_RADIUS);
        List<Mob> hostiles = level.getEntitiesOfClass(Mob.class, box,
                mob -> mob instanceof Enemy && mob.isAlive());
        hostiles.sort(Comparator.comparingDouble(companion::distanceToSqr));

        List<WorldSnapshot.ThreatState> threats = new ArrayList<>();
        for (Mob mob : hostiles) {
            if (threats.size() >= MAX_THREATS) {
                break;
            }
            threats.add(new WorldSnapshot.ThreatState(mob.getUUID(), typeName(mob),
                    companion.distanceTo(mob), compass(mob.getX() - companion.getX(),
                            mob.getZ() - companion.getZ()),
                    mob.getHealth(), mob.getMaxHealth(),
                    mob.getTarget() == companion, companion.hasLineOfSight(mob)));
        }
        return threats;
    }

    private static List<WorldSnapshot.EntityState> entitiesOf(CompanionEntity companion, ServerLevel level) {
        AABB box = companion.getBoundingBox().inflate(ENTITY_RADIUS);
        List<Entity> nearby = level.getEntities(companion, box,
                entity -> entity.isAlive()
                        && (entity instanceof ServerPlayer || entity instanceof Villager));
        nearby.sort(Comparator.comparingDouble(companion::distanceToSqr));

        List<WorldSnapshot.EntityState> states = new ArrayList<>();
        for (Entity entity : nearby) {
            if (states.size() >= MAX_ENTITIES) {
                break;
            }
            states.add(new WorldSnapshot.EntityState(entity.getUUID(), typeName(entity),
                    companion.distanceTo(entity),
                    compass(entity.getX() - companion.getX(), entity.getZ() - companion.getZ())));
        }
        return states;
    }

    private static List<WorldSnapshot.ItemState> hotbarOf(CompanionEntity companion) {
        LivingEntityInventory inventory = companion.getLivingInventory();
        List<WorldSnapshot.ItemState> hotbar = new ArrayList<>(HOTBAR_SLOTS);
        for (int slot = 0; slot < HOTBAR_SLOTS && slot < inventory.main.size(); slot++) {
            hotbar.add(itemState(inventory.main.get(slot)));
        }
        return hotbar;
    }

    private static List<WorldSnapshot.ItemState> equipmentOf(CompanionEntity companion) {
        LivingEntityInventory inventory = companion.getLivingInventory();
        List<WorldSnapshot.ItemState> equipment = new ArrayList<>(5);
        // Vanilla stores armor feet-first; render order is helmet down to boots.
        for (int slot = inventory.armor.size() - 1; slot >= 0; slot--) {
            equipment.add(itemState(inventory.armor.get(slot)));
        }
        equipment.add(inventory.offHand.isEmpty()
                ? WorldSnapshot.ItemState.EMPTY : itemState(inventory.offHand.get(0)));
        return equipment;
    }

    /** Main inventory beyond the hotbar, aggregated by item and ranked by count. */
    private static List<WorldSnapshot.ItemState> notableOf(CompanionEntity companion) {
        return rankedInventory(companion).stream().limit(MAX_NOTABLE).toList();
    }

    private static int omittedKinds(CompanionEntity companion) {
        return Math.max(0, rankedInventory(companion).size() - MAX_NOTABLE);
    }

    private static List<WorldSnapshot.ItemState> rankedInventory(CompanionEntity companion) {
        LivingEntityInventory inventory = companion.getLivingInventory();
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (int slot = HOTBAR_SLOTS; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            totals.merge(itemName(stack), stack.getCount(), Integer::sum);
        }
        return totals.entrySet().stream()
                .map(entry -> new WorldSnapshot.ItemState(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(WorldSnapshot.ItemState::count).reversed())
                .toList();
    }

    private static WorldSnapshot.ActivityState activityOf(CompanionEntity companion) {
        TaskSupervisor supervisor = companion.getSupervisor();
        if (supervisor == null) {
            return null;
        }
        Optional<TaskActivitySnapshot> activity = supervisor.currentActivity();
        Optional<Goal> goal = supervisor.currentGoal();
        if (activity.isEmpty() || goal.isEmpty()) {
            return null;
        }
        TaskActivitySnapshot snapshot = activity.get();
        GoalProgress progress = snapshot.progress();
        String planId = "";
        int planStep = 0;
        int planSteps = 0;
        if (companion.getPlanExecutor() != null && companion.getPlanExecutor().isActive()) {
            planId = companion.getPlanExecutor().currentPlan().map(plan -> plan.id()).orElse("");
            planStep = companion.getPlanExecutor().currentStepIndex() + 1;
            planSteps = companion.getPlanExecutor().currentPlan().map(plan -> plan.steps().size()).orElse(0);
        }
        return new WorldSnapshot.ActivityState(snapshot.goalId(), snapshot.kind(),
                goal.get().description(), snapshot.profile().scope().name().toLowerCase(Locale.ROOT),
                snapshot.elapsedTicks(), snapshot.timeoutTicks(),
                progress == null ? "" : progress.text(),
                snapshot.preemptingChain() == null ? "" : snapshot.preemptingChain(),
                planId, planStep, planSteps);
    }

    private static WorldSnapshot.OutcomeState outcomeOf(CompanionEntity companion) {
        TaskSupervisor supervisor = companion.getSupervisor();
        if (supervisor == null) {
            return null;
        }
        return supervisor.lastOutcome()
                .map(outcome -> new WorldSnapshot.OutcomeState(outcome.kind(), outcome.status().name(),
                        outcome.reason(), outcome.have(), outcome.want(), outcome.elapsedMs()))
                .orElse(null);
    }

    private static WorldSnapshot.ReflexState reflexOf(CompanionEntity companion) {
        ReflexSnapshot reflex = companion.getReflexSnapshot();
        if (reflex == null) {
            return new WorldSnapshot.ReflexState("NONE", "", false, 0);
        }
        return new WorldSnapshot.ReflexState(reflex.kind().name(), reflex.detail(),
                reflex.defenseSuppressed(), companion.reflexActiveTicks());
    }

    private static List<JournalEntry> eventsOf(CompanionEntity companion) {
        CompanionJournal journal = companion.getJournal();
        return journal == null ? List.of() : journal.recent(MAX_EVENTS);
    }

    private static WorldSnapshot.ItemState itemState(ItemStack stack) {
        return stack.isEmpty()
                ? WorldSnapshot.ItemState.EMPTY
                : new WorldSnapshot.ItemState(itemName(stack), stack.getCount());
    }

    private static String itemName(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private static String blockName(ServerLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
    }

    private static String typeName(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
    }

    /** Eight-point compass bearing. North is -Z and east is +X. */
    private static String compass(double dx, double dz) {
        if (Math.abs(dx) < 1.0e-6 && Math.abs(dz) < 1.0e-6) {
            return "here";
        }
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        int index = (int) Math.round(degrees / 45.0);
        return COMPASS[((index % 8) + 8) % 8];
    }
}
