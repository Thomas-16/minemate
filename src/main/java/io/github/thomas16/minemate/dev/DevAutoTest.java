package io.github.thomas16.minemate.dev;

import com.mojang.authlib.GameProfile;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.PlayerEngineSettings;
import com.player2.playerengine.tasks.base.Task;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.debug.CompanionDebugSnapshot;
import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.entity.MineMateEntities;
import io.github.thomas16.minemate.task.Goal;
import io.github.thomas16.minemate.task.ExecutionScope;
import io.github.thomas16.minemate.task.GoalExecutionProfile;
import io.github.thomas16.minemate.task.GoalProgress;
import io.github.thomas16.minemate.task.Goals;
import io.github.thomas16.minemate.task.OutcomeStatus;
import io.github.thomas16.minemate.task.Reasons;
import io.github.thomas16.minemate.task.TaskListener;
import io.github.thomas16.minemate.task.TaskListeners;
import io.github.thomas16.minemate.task.TaskOutcome;
import io.github.thomas16.minemate.integration.CompanionReachability;
import io.github.thomas16.minemate.integration.ReflexKind;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Sequential headless verification for the task supervisor, enabled with
 * {@code gradlew runServer -Dminemate.autotest=true}.
 */
public final class DevAutoTest {
    private static final int GLOBAL_CAP_TICKS = 30_000;
    private static final Set<String> SMOKE_SCENARIOS = Set.of(
            "debug_snapshot",
            "owner_friendly_fire",
            "mine_success",
            "goto_success",
            "cancel",
            "craft_success",
            "fetch_oak_boat_delivery",
            "smelt_success",
            "smelt_wall_reach",
            "deposit_success",
            "container_missing");
    private static final UUID FAKE_OWNER_UUID =
            UUID.fromString("bbf6e9b1-075b-4d78-928d-a73cc6c6155f");
    private static final UUID FRIENDLY_FIRE_OWNER_UUID =
            UUID.fromString("5ec29e35-81d1-4c23-aef9-0e394daaf52d");
    private static final UUID DEBUG_VIEWER_UUID =
            UUID.fromString("e1cdca3b-20c4-4853-9352-474eb62406b7");

    private static CompanionEntity companion;
    private static FakePlayer fakeOwner;
    private static FakePlayer debugViewer;
    private static CompanionDebugSnapshot debugFixtureSnapshot;
    private static Mob hostile;
    private static BlockPos spawn;
    private static BlockPos storagePos;
    private static BlockPos furnacePos;
    private static boolean sawEatingReflex;
    private static boolean sawEatingClear;
    private static boolean sawMobDefenseReflex;
    private static boolean sawMobDefenseClear;
    private static boolean sawStorageReach;
    private static boolean sawFurnaceReach;
    private static boolean ownerDamageAccepted;
    private static boolean ownerDirectAttackBlocked;
    private static float companionHealthBeforeOwnerDamage;
    private static float ownerHealthBeforeFriendlyFire;
    private static Boolean originalHungerEnabled;
    private static Boolean originalAutoEat;
    private static List<Scenario> scenarios = List.of();
    private static int scenarioIndex;
    private static int scenarioTicks;
    private static int globalTicks;
    private static Goal currentGoal;
    private static TaskOutcome currentOutcome;
    private static boolean advanceRequested;
    private static int advanceNotBeforeTick = -1;
    private static String listenerFailure;
    private static boolean done;
    private static final List<BlockPos> worldMaterialPositions = new ArrayList<>();
    private static final List<ItemEntity> observedFetchDrops = new ArrayList<>();
    private static final List<FetchThrowObservation> observedFetchThrows = new ArrayList<>();

    private DevAutoTest() {
    }

    public static void init() {
        if (!Boolean.getBoolean("minemate.autotest")) {
            return;
        }
        MineMate.LOGGER.info("[autotest] enabled - will run task supervisor scenarios");

        TaskListeners.register(new TaskListener() {
            @Override
            public void onGoalFinished(CompanionEntity finishedCompanion, Goal goal, TaskOutcome outcome) {
                if (done || finishedCompanion != companion) {
                    return;
                }
                if (currentGoal == null || !currentGoal.id().equals(goal.id())) {
                    listenerFailure = "unexpected outcome for " + goal.id();
                } else if (currentOutcome != null) {
                    listenerFailure = "duplicate outcome for " + goal.id();
                } else {
                    currentOutcome = outcome;
                    advanceRequested = true;
                }
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(DevAutoTest::start);
        ServerTickEvents.END_SERVER_TICK.register(DevAutoTest::tick);
    }

    private static void start(MinecraftServer server) {
        ServerLevel level = server.overworld();
        spawn = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level.getSharedSpawnPos());
        storagePos = spawn.offset(5, 0, 3);
        furnacePos = spawn.offset(5, 0, -3);
        companion = new CompanionEntity(MineMateEntities.COMPANION, level);
        companion.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0.0F, 0.0F);
        level.addFreshEntity(companion);
        debugViewer = FakePlayer.get(level, new GameProfile(DEBUG_VIEWER_UUID, "MineMateDebugViewer"));
        level.setBlockAndUpdate(spawn.offset(3, 0, 0), Blocks.DIRT.defaultBlockState());

        String idleDebugViolation = debugSnapshotInvariant();
        if (idleDebugViolation != null) {
            fail(server, "debug idle snapshot invalid: " + idleDebugViolation);
            return;
        }

        try {
            Goals.fetch("unobtainium", 1);
            fail(server, "unknown_resource did not reject an invalid catalogue name");
            return;
        } catch (IllegalArgumentException expected) {
            MineMate.LOGGER.info("[autotest] unknown_resource PASS");
        }

        scenarios = List.of(
                new Scenario("debug_snapshot",
                        ignored -> terminalResampleGoal(),
                        outcome -> outcome.status() == OutcomeStatus.SUCCEEDED
                                && Reasons.GOAL_MET.equals(outcome.reason()),
                        100,
                        ignoredServer -> prepareDebugSnapshotFixture(),
                        ignoredServer -> clearDebugSnapshotFixture(),
                        DevAutoTest::debugSnapshotFixtureInvariant),
                new Scenario("owner_friendly_fire",
                        ignored -> reflexObservationGoal(),
                        DevAutoTest::succeeded,
                        400,
                        ignoredServer -> prepareOwnerFriendlyFire(),
                        ignoredServer -> clearOwnerFriendlyFire(),
                        DevAutoTest::ownerFriendlyFireInvariant),
                // EntityTracker observes the fixture reliably before long-range pathing has
                // populated its unreachable/cache state; the submitted wait task still proves
                // real user-chain preemption and resumption.
                new Scenario("mob_defense_reflex",
                        ignored -> reflexObservationGoal(),
                        DevAutoTest::acceptMobDefenseReflex,
                        1200,
                        ignoredServer -> prepareMobDefenseReflex(),
                        DevAutoTest::clearMobDefenseReflex,
                        () -> null),
                new Scenario("mine_success",
                        ignored -> Goals.mine("dirt", 1),
                        outcome -> outcome.status() == OutcomeStatus.SUCCEEDED
                                && Reasons.GOAL_MET.equals(outcome.reason()),
                        2400),
                new Scenario("goto_success",
                        ignored -> Goals.goTo(spawn.getX() + 24, spawn.getZ(), 2.5),
                        outcome -> outcome.status() == OutcomeStatus.SUCCEEDED
                                && Reasons.GOAL_MET.equals(outcome.reason()),
                        2400),
                new Scenario("cancel",
                        ignored -> Goals.goTo(spawn.getX() + 100_000, spawn.getZ(), 2.5),
                        outcome -> outcome.status() == OutcomeStatus.CANCELLED
                                && Reasons.CANCELLED_BY_OWNER.equals(outcome.reason()),
                        400),
                new Scenario("defense_leash",
                        ignored -> Goals.goTo(spawn.getX() + 16, spawn.getZ(), 2.5),
                        outcome -> outcome.status() == OutcomeStatus.SUCCEEDED
                                && Reasons.GOAL_MET.equals(outcome.reason()),
                        2400,
                        ignoredServer -> armHostile(spawn, EntityType.ZOMBIE),
                        DevAutoTest::disarmHostile,
                        DevAutoTest::defenseLeashInvariant),
                new Scenario("craft_success",
                        ignored -> Goals.craft(companion, Items.OAK_PLANKS, 4),
                        outcome -> succeeded(outcome) && inventoryCount(Items.OAK_PLANKS) >= 4,
                        1200,
                        ignoredServer -> prepareInventory(new ItemStack(Items.OAK_PLANKS, 2),
                                new ItemStack(Items.OAK_LOG, 1)),
                        ignoredServer -> clearFixtures(),
                        DevAutoTest::compositeActivityInvariant),
                new Scenario("craft_search_window",
                        ignored -> withTimeout(Goals.craft(companion, Items.IRON_SWORD, 1), 400),
                        outcome -> outcome.status() == OutcomeStatus.TIMED_OUT
                                && Reasons.TIMEOUT.equals(outcome.reason()),
                        600,
                        ignoredServer -> prepareInventory(),
                        ignoredServer -> clearFixtures(),
                        DevAutoTest::craftSearchWindowInvariant),
                new Scenario("craft_iron_sword_world",
                        ignored -> Goals.craft(companion, Items.IRON_SWORD, 1),
                        outcome -> succeeded(outcome) && inventoryCount(Items.IRON_SWORD) >= 1,
                        6200,
                        ignoredServer -> prepareIronSwordWorld(),
                        ignoredServer -> clearFixtures(),
                        DevAutoTest::compositeActivityInvariant),
                new Scenario("fetch_oak_boat_delivery",
                        ignored -> Goals.fetch("oak_boat", 2),
                        DevAutoTest::acceptFetchOakBoatDelivery,
                        6200,
                        ignoredServer -> prepareFetchOakBoats(),
                        DevAutoTest::clearFetchOakBoats,
                        DevAutoTest::fetchDropInvariant),
                new Scenario("craft_no_recipe",
                        ignored -> Goals.craft(companion, Items.BEDROCK, 1),
                        outcome -> outcome.status() == OutcomeStatus.BLOCKED
                                && Reasons.NO_CRAFTING_RECIPE.equals(outcome.reason()),
                        100,
                        ignoredServer -> prepareInventory(),
                        ignoredServer -> clearFixtures(),
                        () -> null),
                new Scenario("smelt_success",
                        ignored -> Goals.smelt(companion, Items.COBBLESTONE, 2),
                        outcome -> succeeded(outcome) && inventoryCount(Items.STONE) >= 2,
                        1600,
                        ignoredServer -> prepareFurnace(new ItemStack(Items.STONE, 1),
                                new ItemStack(Items.COBBLESTONE, 1),
                                new ItemStack(Items.COAL, 1)),
                        ignoredServer -> clearFixtures(),
                        DevAutoTest::directActivityInvariant),
                new Scenario("smelt_wall_reach",
                        ignored -> Goals.smelt(companion, Items.COBBLESTONE, 1),
                        outcome -> succeeded(outcome) && sawFurnaceReach
                                && inventoryCount(Items.STONE) >= 1,
                        1800,
                        ignoredServer -> prepareWalledFurnace(),
                        ignoredServer -> clearFixtures(),
                        DevAutoTest::walledFurnaceInvariant),
                new Scenario("smelt_no_recipe",
                        ignored -> Goals.smelt(companion, Items.BEDROCK, 1),
                        outcome -> outcome.status() == OutcomeStatus.BLOCKED
                                && Reasons.NO_SMELTING_RECIPE.equals(outcome.reason()),
                        100,
                        ignoredServer -> prepareInventory(),
                        ignoredServer -> clearFixtures(),
                        () -> null),
                new Scenario("smelt_no_fuel",
                        ignored -> Goals.smelt(companion, Items.COBBLESTONE, 1),
                        outcome -> outcome.status() == OutcomeStatus.BLOCKED
                                && (Reasons.NO_FUEL.equals(outcome.reason())
                                        || Reasons.SETUP_FAILED.equals(outcome.reason())),
                        1200,
                        ignoredServer -> prepareFurnace(new ItemStack(Items.COBBLESTONE, 1)),
                        ignoredServer -> clearFixtures(),
                        () -> null),
                new Scenario("deposit_success",
                        ignored -> Goals.deposit(companion, storagePos, Items.DIRT, 4),
                        DevAutoTest::acceptDepositSuccess,
                        1200,
                        ignoredServer -> {
                            prepareStorage(2);
                            insert(new ItemStack(Items.DIRT, 2));
                        },
                        ignoredServer -> clearFixtures(),
                        DevAutoTest::directActivityInvariant),
                new Scenario("withdraw_wall_reach",
                        ignored -> Goals.withdraw(companion, storagePos, Items.DIRT, 2),
                        outcome -> succeeded(outcome) && sawStorageReach
                                && storageCount(Items.DIRT) == 0
                                && inventoryCount(Items.DIRT) == 2,
                        1200,
                        ignoredServer -> prepareWalledStorage(),
                        ignoredServer -> clearFixtures(),
                        DevAutoTest::walledStorageInvariant),
                new Scenario("withdraw_partial",
                        ignored -> Goals.withdraw(companion, storagePos, Items.DIRT, 4),
                        outcome -> outcome.status() == OutcomeStatus.BLOCKED
                                && Reasons.INSUFFICIENT_ITEMS.equals(outcome.reason())
                                && outcome.moved() == 2
                                && outcome.have() == 2 && outcome.want() == 4
                                && inventoryCount(Items.DIRT) == 2,
                        1200,
                        ignoredServer -> prepareStorage(2),
                        ignoredServer -> clearFixtures(),
                        () -> null),
                new Scenario("deposit_full",
                        ignored -> Goals.deposit(companion, storagePos, Items.DIRT, 1),
                        outcome -> outcome.status() == OutcomeStatus.BLOCKED
                                && Reasons.INSUFFICIENT_SPACE.equals(outcome.reason())
                                && inventoryCount(Items.DIRT) == 1,
                        1200,
                        ignoredServer -> prepareFullStorage(),
                        ignoredServer -> clearFixtures(),
                        () -> null),
                new Scenario("container_missing",
                        ignored -> Goals.withdraw(companion, storagePos, Items.DIRT, 1),
                        outcome -> outcome.status() == OutcomeStatus.BLOCKED
                                && Reasons.CONTAINER_MISSING.equals(outcome.reason()),
                        100,
                        ignoredServer -> prepareInventory(),
                        ignoredServer -> clearFixtures(),
                        () -> null),
                new Scenario("container_unsupported",
                        ignored -> Goals.withdraw(companion, storagePos, Items.DIRT, 1),
                        outcome -> outcome.status() == OutcomeStatus.BLOCKED
                                && Reasons.CONTAINER_UNSUPPORTED.equals(outcome.reason()),
                        100,
                        ignoredServer -> prepareUnsupportedContainer(),
                        ignoredServer -> clearFixtures(),
                        () -> null),
                new Scenario("container_unreachable",
                        ignored -> Goals.withdraw(companion, storagePos.offset(50_000, 0, 50_000),
                                Items.DIRT, 1),
                        outcome -> outcome.status() == OutcomeStatus.BLOCKED
                                && Reasons.CONTAINER_UNREACHABLE.equals(outcome.reason()),
                        100,
                        ignoredServer -> prepareInventory(),
                        ignoredServer -> clearFixtures(),
                        () -> null),
                new Scenario("eating_reflex",
                        ignored -> reflexObservationGoal(),
                        outcome -> succeeded(outcome) && sawEatingReflex && sawEatingClear,
                        1200,
                        ignoredServer -> prepareEatingReflex(),
                        ignoredServer -> clearFixtures(),
                        () -> null));
        String selectedScenario = System.getProperty("minemate.autotest.scenario", "").trim();
        if (!selectedScenario.isEmpty()) {
            List<String> selectedNames = List.of(selectedScenario.split(",")).stream()
                    .map(String::trim)
                    .filter(name -> !name.isEmpty())
                    .toList();
            List<String> availableNames = scenarios.stream().map(Scenario::name).toList();
            List<String> unknownNames = selectedNames.stream()
                    .filter(name -> !availableNames.contains(name))
                    .toList();
            if (!unknownNames.isEmpty()) {
                fail(server, "unknown selected scenario(s): " + String.join(",", unknownNames));
                return;
            }
            scenarios = scenarios.stream().filter(scenario -> selectedNames.contains(scenario.name())).toList();
            if (scenarios.isEmpty()) {
                fail(server, "unknown selected scenario: " + selectedScenario);
                return;
            }
            MineMate.LOGGER.info("[autotest] profile=focused scenarios={}", scenarios.size());
        } else {
            String profile = System.getProperty("minemate.autotest.profile", "smoke")
                    .trim().toLowerCase(Locale.ROOT);
            if ("smoke".equals(profile)) {
                scenarios = scenarios.stream()
                        .filter(scenario -> SMOKE_SCENARIOS.contains(scenario.name()))
                        .toList();
            } else if (!"full".equals(profile)) {
                fail(server, "unknown autotest profile: " + profile);
                return;
            }
            MineMate.LOGGER.info("[autotest] profile={} scenarios={}", profile, scenarios.size());
        }
        scenarioIndex = 0;
        startScenario(server);
    }

    private static void tick(MinecraftServer server) {
        if (companion == null || done) {
            return;
        }
        if (companion.getReflexSnapshot() != null) {
            if (companion.getReflexSnapshot().kind() == ReflexKind.EATING) {
                sawEatingReflex = true;
            } else if (sawEatingReflex && companion.getReflexSnapshot().kind() == ReflexKind.NONE) {
                sawEatingClear = true;
            }
            if (companion.getReflexSnapshot().kind() == ReflexKind.MOB_DEFENSE) {
                sawMobDefenseReflex = true;
                if (hostile != null) {
                    hostile.discard();
                    hostile = null;
                }
            } else if (sawMobDefenseReflex && companion.getReflexSnapshot().kind() == ReflexKind.NONE) {
                sawMobDefenseClear = true;
            }
        }
        trackFetchDropsAndTouchOwner();
        globalTicks++;
        if (listenerFailure != null) {
            fail(server, listenerFailure);
            return;
        }
        if (globalTicks > GLOBAL_CAP_TICKS) {
            fail(server, "global tick cap exceeded");
            return;
        }
        if (globalTicks % 5 == 0 || advanceRequested) {
            String debugViolation = debugSnapshotInvariant();
            if (debugViolation != null) {
                fail(server, "debug snapshot invalid: " + debugViolation);
                return;
            }
        }
        if (advanceRequested) {
            if (advanceNotBeforeTick < 0) {
                advanceNotBeforeTick = globalTicks + 1;
                return;
            }
            if (globalTicks >= advanceNotBeforeTick) {
                finishScenario(server);
            }
            return;
        }

        scenarioTicks++;
        Scenario scenario = scenarios.get(scenarioIndex);
        String violation = scenario.invariant().get();
        if (violation != null) {
            fail(server, scenario.name() + " invariant broken: " + violation);
            return;
        }
        if ("cancel".equals(scenario.name()) && scenarioTicks == 40) {
            companion.getSupervisor().cancel(Reasons.CANCELLED_BY_OWNER);
        }
        if (scenarioTicks > scenario.hardCapTicks()) {
            fail(server, scenario.name() + " exceeded its hard cap");
        }
    }

    private static void startScenario(MinecraftServer server) {
        Scenario scenario = scenarios.get(scenarioIndex);
        scenarioTicks = 0;
        currentOutcome = null;
        advanceRequested = false;
        advanceNotBeforeTick = -1;
        scenario.setup().accept(server);
        currentGoal = scenario.goal().apply(companion);
        if (companion.getSupervisor().submit(currentGoal)
                != io.github.thomas16.minemate.task.TaskSupervisor.SubmitResult.ACCEPTED) {
            fail(server, scenario.name() + " was not accepted");
            return;
        }
        MineMate.LOGGER.info("[autotest] {} START", scenario.name());
    }

    private static void finishScenario(MinecraftServer server) {
        Scenario scenario = scenarios.get(scenarioIndex);
        boolean accepted = currentOutcome != null && scenario.accepts().test(currentOutcome);
        scenario.teardown().accept(server);
        if (!accepted) {
            fail(server, scenario.name() + " returned "
                    + (currentOutcome == null ? "no outcome" : currentOutcome.toCompactString()));
            return;
        }
        MineMate.LOGGER.info("[autotest] {} PASS - {}", scenario.name(), currentOutcome.toCompactString());
        scenarioIndex++;
        if (scenarioIndex >= scenarios.size()) {
            done = true;
            MineMate.LOGGER.info("[autotest] AUTOTEST PASS - all {} scenarios passed", scenarios.size());
            despawnCompanion();
            server.halt(false);
            return;
        }
        startScenario(server);
    }

    /**
     * The suite's world is provisioned peaceful, where PlayerEngine's mob defense
     * short-circuits to {@code -Infinity} and can never preempt anything. Raising the
     * difficulty and putting a zombie next to the companion is the only way to exercise
     * the leash headlessly. Night time keeps the zombie from burning away mid-scenario.
     */
    private static void armHostile(BlockPos spawn, EntityType<? extends Mob> type) {
        ServerLevel level = (ServerLevel) companion.level();
        // The preceding scenarios chase a goal 100k blocks away, which walks the companion
        // out of the ticking spawn chunks — where it stops being ticked at all, and no goal
        // can ever finish. Start this one from spawn so it stays inside them.
        companion.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        level.getServer().setDifficulty(Difficulty.EASY, true);
        level.setDayTime(18_000L);
        hostile = type.spawn(level, spawn.offset(2, 0, 2), MobSpawnType.COMMAND);
        if (hostile == null) {
            throw new IllegalStateException("Could not spawn the mob-defense fixture");
        }
        hostile.setPersistenceRequired();
    }

    private static void disarmHostile(MinecraftServer server) {
        if (hostile != null) {
            hostile.discard();
            hostile = null;
        }
        if (companion != null) {
            ((ServerLevel) companion.level()).setDayTime(1000L);
        }
        server.setDifficulty(Difficulty.PEACEFUL, true);
    }

    private static String defenseLeashInvariant() {
        PlayerEngineController controller = companion.getController();
        if (!controller.getShouldDefendFromHostiles()) {
            return "mob defense was not suppressed while the companion had no owner to leash to";
        }
        if (controller.getTaskRunner().getCurrentTaskChain() == controller.getMobDefenseChain()) {
            return "mob defense took the body from the user task chain";
        }
        if (companion.getReflexSnapshot() == null || !companion.getReflexSnapshot().defenseSuppressed()) {
            return "reflex telemetry did not expose the defense leash";
        }
        return null;
    }

    private static boolean succeeded(TaskOutcome outcome) {
        return outcome.status() == OutcomeStatus.SUCCEEDED && Reasons.GOAL_MET.equals(outcome.reason());
    }

    private static boolean acceptDepositSuccess(TaskOutcome outcome) {
        int stored = storageCount(Items.DIRT);
        int held = inventoryCount(Items.DIRT);
        boolean accepted = succeeded(outcome) && stored == 4 && held == 0;
        if (!accepted) {
            MineMate.LOGGER.error("[autotest] deposit postcondition stored={} held={}", stored, held);
        }
        return accepted;
    }

    private static String walledStorageInvariant() {
        if (!sawStorageReach
                && CompanionReachability.canReachBlock(companion.getController(), storagePos)) {
            sawStorageReach = true;
        }
        if (!sawStorageReach
                && (storageCount(Items.DIRT) != 2 || inventoryCount(Items.DIRT) != 0)) {
            return "storage contents changed before the companion had an unobstructed reach";
        }
        return null;
    }

    private static String walledFurnaceInvariant() {
        if (!sawFurnaceReach
                && CompanionReachability.canReachBlock(companion.getController(), furnacePos)) {
            sawFurnaceReach = true;
        }
        if (sawFurnaceReach) {
            return null;
        }

        Container furnace = furnaceContainer();
        for (int slot = 0; slot < furnace.getContainerSize(); slot++) {
            if (!furnace.getItem(slot).isEmpty()) {
                return "furnace contents changed before the companion had an unobstructed reach";
            }
        }
        if (inventoryCount(Items.COBBLESTONE) != 1 || inventoryCount(Items.COAL) != 1
                || inventoryCount(Items.STONE) != 0) {
            return "companion inventory changed before the furnace was reachable";
        }
        return null;
    }

    private static boolean acceptMobDefenseReflex(TaskOutcome outcome) {
        boolean accepted = succeeded(outcome) && sawMobDefenseReflex && sawMobDefenseClear;
        if (!accepted) {
            MineMate.LOGGER.error(
                    "[autotest] mob defense postcondition seen={} cleared={} hostile={} difficulty={} suppressed={} priority={}",
                    sawMobDefenseReflex, sawMobDefenseClear,
                    hostile == null ? "null" : "alive=" + hostile.isAlive() + ",removed=" + hostile.isRemoved(),
                    companion.level().getDifficulty(),
                    companion.getController().getShouldDefendFromHostiles(),
                    companion.getController().getMobDefenseChain().getPriority());
        }
        return accepted;
    }

    private static boolean acceptFetchOakBoatDelivery(TaskOutcome outcome) {
        int delivered = fakeOwnerInventoryCount(Items.OAK_BOAT);
        String trajectoryViolation = fetchTrajectoryViolation(true);
        boolean accepted = succeeded(outcome) && delivered >= 2 && trajectoryViolation == null;
        if (!accepted) {
            MineMate.LOGGER.error(
                    "[autotest] fetch postcondition delivered={} logs={} planks={} tables={} boats={} "
                            + "observedDrops={} trajectory={} engineState={} engineLog={}",
                    delivered, inventoryCount(Items.OAK_LOG), inventoryCount(Items.OAK_PLANKS),
                    inventoryCount(Items.CRAFTING_TABLE), inventoryCount(Items.OAK_BOAT),
                    describeObservedDrops(), trajectoryViolation, outcome.engineState(), outcome.engineLog());
        }
        return accepted;
    }

    private static void prepareIronSwordWorld() {
        prepareInventory();
        for (int offset = 0; offset < 5; offset++) {
            placeFixtureBlock(spawn.offset(3 + offset, 0, 6), Blocks.OAK_LOG);
        }
        for (int offset = 0; offset < 8; offset++) {
            placeFixtureBlock(spawn.offset(3 + offset, 0, -6), Blocks.COBBLESTONE);
            placeFixtureBlock(spawn.offset(3 + offset, 0, -7), Blocks.COBBLESTONE);
        }
        placeFixtureBlock(spawn.offset(8, 0, 2), Blocks.IRON_ORE);
        placeFixtureBlock(spawn.offset(9, 0, 2), Blocks.IRON_ORE);
        placeFixtureBlock(spawn.offset(10, 0, 2), Blocks.IRON_ORE);
        placeFixtureBlock(spawn.offset(8, 0, -2), Blocks.COAL_ORE);
        placeFixtureBlock(spawn.offset(9, 0, -2), Blocks.COAL_ORE);
    }

    private static void prepareFetchOakBoats() {
        prepareInventory();
        prepareFakeOwner(FAKE_OWNER_UUID, "MineMateTestOwner");
        observedFetchDrops.clear();
        observedFetchThrows.clear();
        for (int offset = 0; offset < 5; offset++) {
            placeFixtureBlock(spawn.offset(3 + offset, 0, 6), Blocks.OAK_LOG);
        }
    }

    private static void prepareFakeOwner(UUID ownerUuid, String ownerName) {
        ServerLevel level = (ServerLevel) companion.level();
        fakeOwner = FakePlayer.get(level, new GameProfile(ownerUuid, ownerName));
        fakeOwner.getInventory().clearContent();
        fakeOwner.setHealth(fakeOwner.getMaxHealth());
        fakeOwner.moveTo(spawn.getX() - 3.5, spawn.getY(), spawn.getZ() + 0.5, 0.0F, 0.0F);
        if (level.getEntity(fakeOwner.getUUID()) == null) {
            level.addNewPlayer(fakeOwner);
        }
        companion.setOwnerUuid(fakeOwner.getUUID());
        companion.getController().getEntityTracker().setDirty();
        companion.getController().getEntityTracker().ensureUpdated();
    }

    private static void clearFetchOakBoats(MinecraftServer server) {
        clearFakeOwner();
        observedFetchDrops.clear();
        observedFetchThrows.clear();
        clearFixtures();
    }

    private static void clearFakeOwner() {
        companion.setOwnerUuid(null);
        if (fakeOwner != null && !fakeOwner.isRemoved()) {
            ((ServerLevel) companion.level()).removePlayerImmediately(
                    fakeOwner, Entity.RemovalReason.DISCARDED);
        }
        fakeOwner = null;
    }

    private static void prepareOwnerFriendlyFire() {
        prepareInventory();
        companion.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        prepareFakeOwner(FRIENDLY_FIRE_OWNER_UUID, "MineMateFriendlyFireOwner");

        companion.setHealth(13.0F);
        ownerHealthBeforeFriendlyFire = fakeOwner.getHealth();
        ownerDirectAttackBlocked = !companion.doHurtTarget(fakeOwner);
        companionHealthBeforeOwnerDamage = companion.getHealth();
        ownerDamageAccepted = companion.hurt(
                companion.damageSources().playerAttack(fakeOwner), 1.0F);
    }

    private static void clearOwnerFriendlyFire() {
        clearFakeOwner();
        companion.setHealth(companion.getMaxHealth());
        ownerDamageAccepted = false;
        ownerDirectAttackBlocked = false;
        clearFixtures();
    }

    private static String ownerFriendlyFireInvariant() {
        if (!ownerDirectAttackBlocked) {
            return "the direct attack boundary did not exclude the owner";
        }
        if (!ownerDamageAccepted || companion.getHealth() >= companionHealthBeforeOwnerDamage) {
            return "the owner's damage did not reach the companion";
        }
        if (fakeOwner == null || fakeOwner.isRemoved()) {
            return "the fake owner was not available during the retaliation window";
        }
        if (fakeOwner.getHealth() != ownerHealthBeforeFriendlyFire) {
            return "the companion damaged its owner";
        }
        if (companion.getReflexSnapshot() != null
                && companion.getReflexSnapshot().kind() == ReflexKind.PLAYER_DEFENSE) {
            return "owner damage activated PlayerEngine player defense";
        }
        return null;
    }

    private static void trackFetchDropsAndTouchOwner() {
        if (fakeOwner == null || fakeOwner.isRemoved() || companion == null) {
            return;
        }
        ServerLevel level = (ServerLevel) companion.level();
        for (ItemEntity drop : level.getEntitiesOfClass(
                ItemEntity.class, companion.getBoundingBox().inflate(24.0))) {
            if (!observedFetchDrops.contains(drop)) {
                observedFetchDrops.add(drop);
                if (drop.getItem().is(Items.OAK_BOAT)) {
                    Vec3 towardOwner = fakeOwner.position().subtract(drop.position());
                    observedFetchThrows.add(new FetchThrowObservation(
                            drop.getDeltaMovement(), new Vec3(towardOwner.x, 0.0, towardOwner.z)));
                }
            }
        }
        for (ItemEntity drop : level.getEntitiesOfClass(
                ItemEntity.class, fakeOwner.getBoundingBox().inflate(1.0, 0.5, 1.0))) {
            if (!observedFetchDrops.contains(drop)) {
                observedFetchDrops.add(drop);
                if (drop.getItem().is(Items.OAK_BOAT)) {
                    Vec3 towardOwner = fakeOwner.position().subtract(drop.position());
                    observedFetchThrows.add(new FetchThrowObservation(
                            drop.getDeltaMovement(), new Vec3(towardOwner.x, 0.0, towardOwner.z)));
                }
            }
            if (drop.getItem().is(Items.OAK_BOAT)) {
                drop.playerTouch(fakeOwner);
            }
        }
    }

    private static String fetchDropInvariant() {
        String trajectoryViolation = fetchTrajectoryViolation(false);
        if (trajectoryViolation != null) {
            return trajectoryViolation;
        }
        for (ItemEntity drop : observedFetchDrops) {
            // GiveItemToPlayerTask hands boats to the fake owner through vanilla
            // playerTouch, whose removed entity may retain its old stack object.
            // The regression under test is CompanionEntity collecting ingredients.
            if (drop.isRemoved() && !drop.getItem().isEmpty()
                    && !drop.getItem().is(Items.OAK_BOAT)) {
                return "removed item entity retained " + drop.getItem();
            }
            if (!drop.isRemoved() && drop.getItem().isEmpty()) {
                return "live item entity had an empty stack";
            }
        }
        return compositeActivityInvariant();
    }

    private static String fetchTrajectoryViolation(boolean requireObservation) {
        if (observedFetchThrows.isEmpty()) {
            return requireObservation ? "no oak boat delivery drop was observed" : null;
        }
        for (FetchThrowObservation observation : observedFetchThrows) {
            Vec3 velocity = observation.velocity();
            Vec3 horizontalVelocity = new Vec3(velocity.x, 0.0, velocity.z);
            Vec3 towardOwner = observation.towardOwner();
            if (horizontalVelocity.length() < 0.08) {
                return "delivery drop had no meaningful horizontal velocity: " + velocity;
            }
            if (towardOwner.lengthSqr() > 1.0e-6
                    && horizontalVelocity.normalize().dot(towardOwner.normalize()) < 0.9) {
                return "delivery drop was not aimed at owner: velocity=" + velocity
                        + " towardOwner=" + towardOwner;
            }
        }
        return null;
    }

    private static String describeObservedDrops() {
        return "entities=" + observedFetchDrops.stream()
                .map(drop -> "[removed=" + drop.isRemoved() + ",stack=" + drop.getItem() + "]")
                .toList() + ", throws=" + observedFetchThrows;
    }

    private record FetchThrowObservation(Vec3 velocity, Vec3 towardOwner) {
    }

    private static String craftSearchWindowInvariant() {
        double dx = companion.getX() - (spawn.getX() + 0.5);
        double dz = companion.getZ() - (spawn.getZ() + 0.5);
        if (dx * dx + dz * dz > 64.0) {
            companion.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        }
        return compositeActivityInvariant();
    }

    private static void placeFixtureBlock(BlockPos position, Block block) {
        ServerLevel level = (ServerLevel) companion.level();
        level.setBlockAndUpdate(position, block.defaultBlockState());
        level.setBlockAndUpdate(position.above(), Blocks.AIR.defaultBlockState());
        worldMaterialPositions.add(position);
    }

    private static void clearWorldMaterials() {
        if (companion == null || worldMaterialPositions.isEmpty()) {
            return;
        }
        ServerLevel level = (ServerLevel) companion.level();
        for (BlockPos position : worldMaterialPositions) {
            level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        }
        worldMaterialPositions.clear();
    }

    private static void prepareInventory(ItemStack... stacks) {
        ServerLevel level = (ServerLevel) companion.level();
        clearWorldMaterials();
        companion.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        companion.getLivingInventory().clearContent();
        companion.getHungerManager().setFoodLevel(20);
        companion.getHungerManager().setSaturationLevel(5.0F);
        removeContainerFixtureWithoutDrops(level, storagePos);
        removeContainerFixtureWithoutDrops(level, furnacePos);
        for (ItemStack stack : stacks) {
            insert(stack.copy());
        }
    }

    private static void prepareDebugSnapshotFixture() {
        prepareInventory();
        companion.setOwnerUuid(debugViewer.getUUID());
        companion.getLivingInventory().main.set(11, new ItemStack(Items.DIRT, 7));
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        pickaxe.setDamageValue(7);
        companion.getLivingInventory().main.set(12, pickaxe);
        companion.getLivingInventory().armor.set(3, new ItemStack(Items.IRON_HELMET));
        companion.getLivingInventory().offHand.set(0, new ItemStack(Items.TORCH, 12));
        companion.getLivingInventory().selectedSlot = 2;
        companion.getHungerManager().setFoodLevel(7);
        companion.getHungerManager().setSaturationLevel(1.5F);
        companion.getHungerManager().setExhaustion(2.25F);
        debugFixtureSnapshot = CompanionDebugSnapshot.capture(companion, debugViewer);
    }

    private static void clearDebugSnapshotFixture() {
        companion.setOwnerUuid(null);
        companion.getLivingInventory().selectedSlot = 0;
        companion.getHungerManager().setExhaustion(0.0F);
        debugFixtureSnapshot = null;
        clearFixtures();
    }

    private static String debugSnapshotFixtureInvariant() {
        CompanionDebugSnapshot snapshot = debugFixtureSnapshot;
        if (snapshot == null) {
            return "initial fixture snapshot was absent";
        }
        if (!snapshot.ownerId().equals(debugViewer.getUUID())
                || !"MineMateDebugViewer".equals(snapshot.ownerName())) {
            return "owner identity was not preserved";
        }
        if (snapshot.mainInventory().size() != 36 || snapshot.armorInventory().size() != 4) {
            return "inventory shape was not 36 main plus 4 armor slots";
        }
        if (!snapshot.mainInventory().get(11).is(Items.DIRT)
                || snapshot.mainInventory().get(11).getCount() != 7) {
            return "main[11] was not the exact dirt stack";
        }
        if (!snapshot.mainInventory().get(12).is(Items.IRON_PICKAXE)
                || snapshot.mainInventory().get(12).getDamageValue() != 7) {
            return "main[12] did not preserve item damage";
        }
        if (!snapshot.armorInventory().get(3).is(Items.IRON_HELMET)) {
            return "head armor slot was not preserved";
        }
        if (!snapshot.offhand().is(Items.TORCH) || snapshot.offhand().getCount() != 12) {
            return "offhand stack was not preserved";
        }
        if (snapshot.selectedSlot() != 2) {
            return "selected hotbar slot was " + snapshot.selectedSlot();
        }
        if (snapshot.food() != 7 || snapshot.saturation() != 1.5F || snapshot.exhaustion() != 2.25F) {
            return "hunger fields were not preserved";
        }
        CompanionDebugSnapshot liveSnapshot = CompanionDebugSnapshot.capture(companion, debugViewer);
        if (scenarioTicks >= 2 && (liveSnapshot.goal() == null || liveSnapshot.activity() == null)) {
            return "active goal telemetry was absent";
        }
        return null;
    }

    /** Compares the debug DTO against its canonical sources throughout every scenario. */
    private static String debugSnapshotInvariant() {
        if (debugViewer == null) {
            return "debug viewer was not initialized";
        }
        CompanionDebugSnapshot snapshot = CompanionDebugSnapshot.capture(companion, debugViewer);
        if (!snapshot.companionId().equals(companion.getUUID())
                || snapshot.entityId() != companion.getId()) {
            return "entity identity mismatch";
        }
        if (snapshot.mainInventory().size() != companion.getLivingInventory().main.size()
                || snapshot.armorInventory().size() != companion.getLivingInventory().armor.size()) {
            return "inventory size mismatch";
        }
        if (snapshot.selectedSlot() != companion.getLivingInventory().selectedSlot
                || snapshot.food() != companion.getHungerManager().getFoodLevel()
                || Float.compare(snapshot.saturation(), companion.getHungerManager().getSaturationLevel()) != 0
                || Float.compare(snapshot.exhaustion(), companion.getHungerManager().getExhaustion()) != 0) {
            return "inventory or hunger scalar mismatch";
        }

        Optional<Goal> goal = companion.getSupervisor().currentGoal();
        if (goal.isPresent() != (snapshot.goal() != null)) {
            return "goal presence mismatch";
        }
        if (goal.isPresent() && (!goal.get().id().equals(snapshot.goal().id())
                || !goal.get().kind().equals(snapshot.goal().kind())
                || !goal.get().description().equals(snapshot.goal().description())
                || !goal.get().executionProfile().scope().name().equals(snapshot.goal().scope()))) {
            return "goal detail mismatch";
        }

        Optional<GoalProgress> progress = companion.getSupervisor().currentProgress();
        if (progress.isPresent() != (snapshot.progress() != null)) {
            return "progress presence mismatch";
        }
        if (progress.isPresent() && (progress.get().have() != snapshot.progress().have()
                || progress.get().want() != snapshot.progress().want()
                || Double.compare(progress.get().advance(), snapshot.progress().advance()) != 0
                || !Objects.equals(progress.get().text(), snapshot.progress().text()))) {
            return "progress detail mismatch";
        }

        Optional<io.github.thomas16.minemate.task.TaskActivitySnapshot> activity =
                companion.getSupervisor().currentActivity();
        if (activity.isPresent() != (snapshot.activity() != null)) {
            return "activity presence mismatch";
        }
        if (activity.isPresent() && (activity.get().elapsedTicks() != snapshot.activity().elapsedTicks()
                || activity.get().timeoutTicks() != snapshot.activity().timeoutTicks()
                || !activity.get().phase().equals(snapshot.activity().phase())
                || !activity.get().taskTree().equals(snapshot.activity().taskTree())
                || !activity.get().preemptingChain().equals(snapshot.activity().preemptingChain()))) {
            return "activity detail mismatch";
        }

        Optional<TaskOutcome> outcome = companion.getSupervisor().lastOutcome();
        if (outcome.isPresent() != (snapshot.lastOutcome() != null)) {
            return "outcome presence mismatch";
        }
        if (outcome.isPresent() && (!outcome.get().goalId().equals(snapshot.lastOutcome().goalId())
                || !outcome.get().kind().equals(snapshot.lastOutcome().kind())
                || !outcome.get().status().name().equals(snapshot.lastOutcome().status())
                || !outcome.get().reason().equals(snapshot.lastOutcome().reason())
                || outcome.get().moved() != snapshot.lastOutcome().moved()
                || outcome.get().have() != snapshot.lastOutcome().have()
                || outcome.get().want() != snapshot.lastOutcome().want()
                || outcome.get().elapsedMs() != snapshot.lastOutcome().elapsedMs()
                || !Objects.equals(outcome.get().engineState(), snapshot.lastOutcome().engineState())
                || !Objects.equals(outcome.get().engineLog(), snapshot.lastOutcome().engineLog()))) {
            return "outcome detail mismatch";
        }
        if (!companion.getReflexSnapshot().kind().name().equals(snapshot.reflex().kind())
                || !companion.getReflexSnapshot().chain().equals(snapshot.reflex().chain())
                || companion.getReflexSnapshot().defenseSuppressed() != snapshot.reflex().defenseSuppressed()) {
            return "reflex detail mismatch";
        }
        return null;
    }

    private static void removeContainerFixtureWithoutDrops(ServerLevel level, BlockPos position) {
        BlockEntity blockEntity = level.getBlockEntity(position);
        if (blockEntity instanceof Container container) {
            container.clearContent();
            container.setChanged();
        }
        level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
    }

    private static void prepareFurnace(ItemStack... stacks) {
        prepareInventory(stacks);
        ((ServerLevel) companion.level()).setBlockAndUpdate(furnacePos, Blocks.FURNACE.defaultBlockState());
    }

    private static void prepareWalledFurnace() {
        prepareFurnace(new ItemStack(Items.COBBLESTONE, 1), new ItemStack(Items.COAL, 1));
        sawFurnaceReach = false;
        placeReachWall(furnacePos);
    }

    private static void prepareStorage(int dirtCount) {
        prepareInventory();
        ServerLevel level = (ServerLevel) companion.level();
        level.setBlockAndUpdate(storagePos, Blocks.CHEST.defaultBlockState());
        Container container = storageContainer();
        container.clearContent();
        if (dirtCount > 0) {
            container.setItem(0, new ItemStack(Items.DIRT, dirtCount));
        }
        container.setChanged();
    }

    private static void prepareWalledStorage() {
        prepareStorage(2);
        sawStorageReach = false;
        placeReachWall(storagePos);
    }

    /** Places a two-high screen directly west of the target, with a route around either edge. */
    private static void placeReachWall(BlockPos target) {
        ServerLevel level = (ServerLevel) companion.level();
        for (int zOffset = -1; zOffset <= 1; zOffset++) {
            for (int yOffset = 0; yOffset <= 1; yOffset++) {
                BlockPos wallPos = target.offset(-1, yOffset, zOffset);
                level.setBlockAndUpdate(wallPos, Blocks.BEDROCK.defaultBlockState());
                worldMaterialPositions.add(wallPos);
            }
        }
        BlockPos blockedSide = target.offset(-2, 0, 0);
        companion.teleportTo(blockedSide.getX() + 0.5, blockedSide.getY(), blockedSide.getZ() + 0.5);
    }

    private static void prepareFullStorage() {
        prepareStorage(0);
        Container container = storageContainer();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            container.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        container.setChanged();
        insert(new ItemStack(Items.DIRT, 1));
    }

    private static void prepareUnsupportedContainer() {
        prepareInventory();
        ((ServerLevel) companion.level()).setBlockAndUpdate(
                storagePos, Blocks.ENDER_CHEST.defaultBlockState());
    }

    private static void prepareEatingReflex() {
        prepareInventory(new ItemStack(Items.BREAD, 1));
        originalHungerEnabled = setSetting("hungerEnabled", true);
        originalAutoEat = setSetting("autoEat", true);
        companion.getHungerManager().setHungerEnabled(true);
        companion.getHungerManager().setFoodLevel(6);
        companion.getHungerManager().setSaturationLevel(0.0F);
        sawEatingReflex = false;
        sawEatingClear = false;
    }

    private static void prepareMobDefenseReflex() {
        prepareInventory();
        System.setProperty("minemate.autotest.unleashMobDefense", "true");
        sawMobDefenseReflex = false;
        sawMobDefenseClear = false;
        armHostile(spawn, EntityType.ZOMBIE);
        companion.getController().getEntityTracker().setDirty();
        companion.getController().getEntityTracker().ensureUpdated();
        companion.getController().getMobDefenseChain().resetForceField();
        companion.getController().getMobDefenseChain().setTargetEntity(hostile);
    }

    private static void clearMobDefenseReflex(MinecraftServer server) {
        System.clearProperty("minemate.autotest.unleashMobDefense");
        disarmHostile(server);
        clearFixtures();
    }

    private static void clearFixtures() {
        if (companion == null) {
            return;
        }
        ServerLevel level = (ServerLevel) companion.level();
        clearWorldMaterials();
        companion.getLivingInventory().clearContent();
        restoreEatingSettings();
        companion.getHungerManager().setFoodLevel(20);
        companion.getHungerManager().setSaturationLevel(5.0F);
        removeContainerFixtureWithoutDrops(level, storagePos);
        removeContainerFixtureWithoutDrops(level, furnacePos);
        companion.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
    }

    private static Boolean setSetting(String name, boolean value) {
        try {
            Field field = PlayerEngineSettings.class.getDeclaredField(name);
            field.setAccessible(true);
            PlayerEngineSettings settings = companion.getController().getModSettings();
            boolean previous = field.getBoolean(settings);
            field.setBoolean(settings, value);
            return previous;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not configure PlayerEngine setting " + name, exception);
        }
    }

    private static void restoreEatingSettings() {
        if (originalHungerEnabled == null || originalAutoEat == null) {
            return;
        }
        setSetting("hungerEnabled", originalHungerEnabled);
        setSetting("autoEat", originalAutoEat);
        companion.getHungerManager().setHungerEnabled(originalHungerEnabled);
        originalHungerEnabled = null;
        originalAutoEat = null;
    }

    private static void insert(ItemStack stack) {
        if (!companion.getLivingInventory().insertStack(stack)) {
            throw new IllegalStateException("Could not seed companion inventory with " + stack);
        }
    }

    private static int inventoryCount(Item item) {
        int count = 0;
        Container inventory = companion.getLivingInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                count += inventory.getItem(slot).getCount();
            }
        }
        return count;
    }

    private static int fakeOwnerInventoryCount(Item item) {
        if (fakeOwner == null) {
            return 0;
        }
        int count = 0;
        Container inventory = fakeOwner.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                count += inventory.getItem(slot).getCount();
            }
        }
        return count;
    }

    private static int storageCount(Item item) {
        Container container = storageContainer();
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                count += container.getItem(slot).getCount();
            }
        }
        return count;
    }

    private static Container storageContainer() {
        BlockEntity blockEntity = companion.level().getBlockEntity(storagePos);
        if (!(blockEntity instanceof Container container)) {
            throw new IllegalStateException("Storage fixture is not a container at " + storagePos);
        }
        return container;
    }

    private static Container furnaceContainer() {
        BlockEntity blockEntity = companion.level().getBlockEntity(furnacePos);
        if (!(blockEntity instanceof Container container)) {
            throw new IllegalStateException("Furnace fixture is not a container at " + furnacePos);
        }
        return container;
    }

    private static String compositeActivityInvariant() {
        return activityInvariant(ExecutionScope.COMPOSITE);
    }

    private static String directActivityInvariant() {
        return activityInvariant(ExecutionScope.DIRECT);
    }

    private static String activityInvariant(ExecutionScope expectedScope) {
        if (scenarioTicks < 2) {
            return null;
        }
        Optional<io.github.thomas16.minemate.task.TaskActivitySnapshot> activity =
                companion.getSupervisor().currentActivity();
        if (activity.isEmpty()) {
            return "missing activity snapshot";
        }
        if (activity.get().profile().scope() != expectedScope) {
            return "activity scope was " + activity.get().profile().scope() + ", expected " + expectedScope;
        }
        if (activity.get().phase().isBlank() && activity.get().taskTree().isBlank()) {
            return "activity exposed neither a phase nor a task tree";
        }
        return null;
    }

    private static Goal reflexObservationGoal() {
        return new Goal(Goal.nextId(), "test", "observe a reflex transition",
                GoalExecutionProfile.direct("Autotest-only reflex observation"),
                WaitTicksTask::new,
                context -> GoalProgress.counted(((WaitTicksTask) context.task()).isFinished() ? 1 : 0, 1,
                        "waiting for reflex telemetry to clear"),
                1000, 0);
    }

    private static Goal withTimeout(Goal goal, int timeoutTicks) {
        return new Goal(goal.id(), goal.kind(), goal.description(), goal.executionProfile(),
                goal.taskFactory(), goal.probe(), timeoutTicks, goal.stallTicks());
    }

    private static Goal terminalResampleGoal() {
        return new Goal(Goal.nextId(), "test", "verify terminal progress",
                io.github.thomas16.minemate.task.GoalExecutionProfile.direct("Autotest-only delayed completion"),
                DelayedSuccessTask::new,
                context -> {
                    boolean complete = ((DelayedSuccessTask) context.task()).isFinished();
                    return GoalProgress.counted(complete ? 1 : 0, 1,
                            complete ? "terminal progress visible" : "waiting for terminal progress");
                },
                100, 0);
    }

    private static void fail(MinecraftServer server, String reason) {
        done = true;
        MineMate.LOGGER.error("[autotest] AUTOTEST FAIL - {}", reason);
        if (scenarioIndex < scenarios.size()) {
            scenarios.get(scenarioIndex).teardown().accept(server);
        }
        despawnCompanion();
        server.halt(false);
    }

    /**
     * The suite spawns its companion directly rather than through a command, so nothing
     * else removes it. Left behind it would be saved into the world and reloaded — and
     * ticking — on every later run. Only safe to call once {@code done} is set, so the
     * shutdown outcome is not mistaken for a scenario result.
     */
    private static void despawnCompanion() {
        if (companion != null && !companion.isRemoved()) {
            companion.discard();
        }
        companion = null;
    }

    /**
     * @param setup     prepares the world before the goal is submitted
     * @param teardown  undoes {@code setup}, on pass and on fail alike, so scenarios stay isolated
     * @param invariant checked every tick; a non-null return fails the scenario with that message
     */
    private record Scenario(String name, Function<CompanionEntity, Goal> goal,
            Predicate<TaskOutcome> accepts, int hardCapTicks,
            Consumer<MinecraftServer> setup, Consumer<MinecraftServer> teardown,
            Supplier<String> invariant) {

        private Scenario(String name, Function<CompanionEntity, Goal> goal,
                Predicate<TaskOutcome> accepts, int hardCapTicks) {
            this(name, goal, accepts, hardCapTicks, server -> {
            }, server -> {
            }, () -> null);
        }
    }

    /** Finishes between supervisor sample intervals to exercise terminal re-sampling. */
    private static final class DelayedSuccessTask extends Task {
        private int taskTicks;
        private boolean done;

        @Override
        protected void onStart() {
            taskTicks = 0;
            done = false;
        }

        @Override
        protected Task onTick() {
            taskTicks++;
            if (taskTicks >= 2) {
                done = true;
                stop();
            }
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
            return other instanceof DelayedSuccessTask;
        }

        @Override
        protected String toDebugString() {
            return "Delayed success autotest task";
        }
    }

    /** Long enough for FoodChain to preempt, eat, clear, and return the body. */
    private static final class WaitTicksTask extends Task {
        private int taskTicks;
        private boolean done;

        @Override
        protected void onStart() {
            taskTicks = 0;
            done = false;
        }

        @Override
        protected Task onTick() {
            taskTicks++;
            if (taskTicks >= 120) {
                done = true;
                stop();
            }
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
            return other instanceof WaitTicksTask;
        }

        @Override
        protected String toDebugString() {
            return "Wait for reflex telemetry";
        }
    }
}
