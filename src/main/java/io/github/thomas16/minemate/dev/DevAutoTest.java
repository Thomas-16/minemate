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
import io.github.thomas16.minemate.task.ItemQuantity;
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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.thomas16.minemate.context.CompanionJournal;
import io.github.thomas16.minemate.context.EventKind;
import io.github.thomas16.minemate.context.JournalEntry;
import io.github.thomas16.minemate.context.WorldSnapshot;
import io.github.thomas16.minemate.planner.HostedPlanner;
import io.github.thomas16.minemate.planner.OrderContract;
import io.github.thomas16.minemate.planner.Plan;
import io.github.thomas16.minemate.planner.PlanDisposition;
import io.github.thomas16.minemate.planner.PlanExecutor;
import io.github.thomas16.minemate.planner.PlanOutcome;
import io.github.thomas16.minemate.planner.PlanRequest;
import io.github.thomas16.minemate.planner.PlanStatus;
import io.github.thomas16.minemate.planner.PlanStep;
import io.github.thomas16.minemate.planner.PlanStepOutcome;
import io.github.thomas16.minemate.planner.PlannerAutonomy;
import io.github.thomas16.minemate.planner.PlannerConfig;
import io.github.thomas16.minemate.planner.PlannerDispatch;
import io.github.thomas16.minemate.planner.Planners;
import io.github.thomas16.minemate.planner.ScriptedPlanner;
import io.github.thomas16.minemate.planner.StandingOrder;
import io.github.thomas16.minemate.planner.llm.AnthropicMessagesBackend;
import io.github.thomas16.minemate.planner.llm.LlmException;
import io.github.thomas16.minemate.planner.llm.LlmPrompt;
import io.github.thomas16.minemate.planner.llm.LlmReply;
import io.github.thomas16.minemate.planner.llm.OpenAiResponsesBackend;
import io.github.thomas16.minemate.planner.llm.OpenAiToolSchemas;
import io.github.thomas16.minemate.planner.llm.PlannerPrompt;
import io.github.thomas16.minemate.planner.llm.PlannerProtocol;
import io.github.thomas16.minemate.tool.ToolCall;
import io.github.thomas16.minemate.tool.ToolRegistry;
import io.github.thomas16.minemate.tool.ToolValidation;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            "fetch_plain_resource",
            "fetch_items_delivery",
            "smelt_success",
            "smelt_wall_reach",
            "deposit_success",
            "container_missing",
            "plan_multi_step",
            "plan_step_failure_aborts",
            // Offline and fast: the whole hosted-planner failure surface costs a few hundred ticks.
            "llm_tool_schema",
            "llm_protocol",
            "llm_claude_tool_schema",
            "llm_claude_protocol",
            "llm_claude_plan",
            "llm_unknown_tool",
            "llm_speechless_action",
            "llm_malformed_output",
            "llm_http_error",
            "llm_talk_only",
            "llm_contract_repair",
            "llm_fetch_items_coverage",
            "llm_contract_only_continuation",
            "llm_contract_only_exhausted",
            "llm_contract_exhausted",
            "llm_answered");
    /** Scenarios that make real network calls. Selectable by name, never included in a profile. */
    private static final Set<String> LIVE_SCENARIOS = Set.of("llm_live", "llm_live_multi_step");
    private static final UUID FAKE_OWNER_UUID =
            UUID.fromString("bbf6e9b1-075b-4d78-928d-a73cc6c6155f");
    /** A distinct profile because Fabric caches discarded FakePlayer instances by profile. */
    private static final UUID PLAIN_FETCH_OWNER_UUID =
            UUID.fromString("8b06ad2a-c45e-4e71-89ef-870f7cf9614c");
    private static final UUID PIPELINE_FETCH_OWNER_UUID =
            UUID.fromString("4d1af7d3-9c26-4b8e-b1d2-6f0e58c92a41");
    private static final UUID UNAVAILABLE_FETCH_OWNER_UUID =
            UUID.fromString("a92c33e5-1e74-4f0a-bd68-30c5f1de77b2");
    private static final UUID FETCH_ITEMS_OWNER_UUID =
            UUID.fromString("6c1f4b8e-2d3a-4c5f-9b7e-1a2d3f4c5b6e");
    private static final UUID BEST_EFFORT_OWNER_UUID =
            UUID.fromString("9d8c7b6a-5e4f-4a3b-8c2d-7f6e5d4c3b2a");
    private static final UUID FETCH_ITEMS_LLM_OWNER_UUID =
            UUID.fromString("2e7a9c4d-8b1f-4e6a-a3c5-9d8e7f6a5b4c");
    /**
     * llm_claude_plan completes a real delivery, and a FakePlayer that has been delivered to,
     * discarded and re-added from Fabric's profile cache stops receiving deliveries. Every
     * delivery-completing fixture therefore owns its profile outright.
     */
    private static final UUID CLAUDE_PLAN_OWNER_UUID =
            UUID.fromString("7f2b8c14-5a63-4d97-9e08-c41d72ab6f35");
    private static final UUID FRIENDLY_FIRE_OWNER_UUID =
            UUID.fromString("5ec29e35-81d1-4c23-aef9-0e394daaf52d");
    private static final UUID DEBUG_VIEWER_UUID =
            UUID.fromString("e1cdca3b-20c4-4853-9352-474eb62406b7");
    private static final UUID CONTEXT_OWNER_UUID =
            UUID.fromString("3a0d9d64-6a5a-4f1e-8f2b-2b1f5c8c1a77");

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
    private static boolean provokeHostileEachTick;
    private static boolean sawStorageReach;
    private static boolean sawFurnaceReach;
    private static boolean planSawForbiddenGoal;
    private static FixtureTransport llmTransport;
    private static boolean llmBudgetSawFinalPlanOpen;
    private static Plan llmObservedLivePlan;
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
    private static Plan currentPlan;
    private static PlanOutcome currentPlanOutcome;
    private static String planOutcomeBaseline = "";
    private static boolean advanceRequested;
    private static int advanceNotBeforeTick = -1;
    private static String listenerFailure;
    private static boolean done;
    private static final List<BlockPos> worldMaterialPositions = new ArrayList<>();
    private static final List<ItemEntity> observedFetchDrops = new ArrayList<>();
    private static final List<FetchThrowObservation> observedFetchThrows = new ArrayList<>();
    /** Composite phase words whose appearance in progress text the pipeline scenario asserts. */
    private static final Set<String> PIPELINE_PHASE_WORDS = Set.of(
            "mining", "smelting", "crafting", "searching", "approaching");
    private static final Set<String> observedPipelinePhases = new HashSet<>();

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
                // A plan legitimately produces several goal outcomes that no currentGoal matches,
                // and a probe may drive whole plans of its own, so the goal-identity checks below
                // only apply to goal-shaped scenarios.
                if (done || finishedCompanion != companion || inPlanScenario() || inProbeScenario()) {
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

        String registryViolation = toolRegistryViolation();
        if (registryViolation != null) {
            fail(server, "tool registry: " + registryViolation);
            return;
        }
        MineMate.LOGGER.info("[autotest] tool_registry PASS");

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
                // A plain catalogue resource, not a craftable one. It takes the TaskCatalogue branch
                // of createAcquisitionTask rather than the craft-macro branch that oak_boat takes.
                new Scenario("fetch_plain_resource",
                        ignored -> Goals.fetch("oak_log", 2),
                        outcome -> succeeded(outcome) && fakeOwnerInventoryCount(Items.OAK_LOG) >= 2,
                        6200,
                        ignoredServer -> prepareFetchPlainResource(),
                        DevAutoTest::clearFetchPlainResource,
                        () -> null),
                // The full mine -> smelt -> craft -> deliver pipeline behind one fetch. On the old
                // probes this died as a false BLOCKED when a transient descendant failure surfaced
                // mid-pipeline; it also asserts a floor on phase reporting: at least one progress
                // sample must name a composite phase instead of an opaque "collecting".
                new Scenario("fetch_composite_pipeline",
                        ignored -> Goals.fetch("bucket", 1),
                        DevAutoTest::acceptFetchCompositePipeline,
                        6200,
                        ignoredServer -> prepareFetchCompositePipeline(),
                        DevAutoTest::clearFetchCompositePipeline,
                        DevAutoTest::fetchCompositePipelineInvariant),
                // A genuinely unavailable resource must still block promptly: no inventory change
                // means no local re-plan attempt is funded, so the engine watchdog's failure
                // surfaces as BLOCKED well inside this cap instead of the 6000-tick goal timeout.
                new Scenario("fetch_unavailable_bounded",
                        ignored -> Goals.fetch("bucket", 1),
                        outcome -> outcome.status() == OutcomeStatus.BLOCKED
                                && Reasons.RESOURCE_UNAVAILABLE.equals(outcome.reason()),
                        3200,
                        ignoredServer -> prepareFetchUnavailable(),
                        DevAutoTest::clearFetchUnavailable,
                        DevAutoTest::craftSearchWindowInvariant),
                // One goal, several final items, delivered in sequence. Both entries are already
                // held, so this exercises pure sequential delivery and the entries-delivered
                // outcome shape without acquisition time.
                new Scenario("fetch_items_delivery",
                        ignored -> Goals.fetchItems(List.of(new ItemQuantity("oak_planks", 4),
                                new ItemQuantity("stick", 4))),
                        DevAutoTest::acceptFetchItemsDelivery,
                        2400,
                        ignoredServer -> prepareFetchItemsDelivery(),
                        DevAutoTest::clearFetchItemsDelivery,
                        () -> null),
                // Best effort: the unavailable first entry is skipped, the held second entry still
                // delivers, and the outcome is an honest BLOCKED whose detail names both. The held
                // item must be useless to the bucket pipeline - held planks fund a crafting table
                // and the attempt "makes progress" instead of exhausting - so it is dirt.
                new Scenario("fetch_items_best_effort",
                        ignored -> Goals.fetchItems(List.of(new ItemQuantity("bucket", 1),
                                new ItemQuantity("dirt", 2))),
                        DevAutoTest::acceptFetchItemsBestEffort,
                        4000,
                        ignoredServer -> prepareFetchItemsBestEffort(),
                        DevAutoTest::clearFetchItemsBestEffort,
                        () -> null),
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
                        () -> null),
                new Scenario("context_journal",
                        ignored -> reflexObservationGoal(),
                        DevAutoTest::acceptContextJournal,
                        400,
                        ignoredServer -> prepareContextJournal(),
                        DevAutoTest::clearContextJournal,
                        DevAutoTest::contextJournalInvariant),
                Scenario.plan("plan_multi_step",
                        ignored -> planOf("stash and retrieve",
                                ToolCall.of("deposit").with("container", storagePos)
                                        .with("item", Items.DIRT).with("count", 4),
                                ToolCall.of("withdraw").with("container", storagePos)
                                        .with("item", Items.DIRT).with("count", 4),
                                ToolCall.of("goto").with("x", spawn.getX() + 8)
                                        .with("z", spawn.getZ())),
                        DevAutoTest::acceptPlanMultiStep,
                        1800,
                        ignoredServer -> preparePlanMultiStep(),
                        ignoredServer -> clearFixtures(),
                        () -> null),
                Scenario.plan("plan_step_failure_aborts",
                        ignored -> planOf("smelt then keep working",
                                ToolCall.of("smelt").with("item", Items.COBBLESTONE).with("count", 1),
                                ToolCall.of("mine").with("resource", "dirt").with("count", 1),
                                ToolCall.of("goto").with("x", spawn.getX() + 8)
                                        .with("z", spawn.getZ())),
                        DevAutoTest::acceptPlanStepFailureAborts,
                        1200,
                        ignoredServer -> {
                            planSawForbiddenGoal = false;
                            prepareFurnace(new ItemStack(Items.COBBLESTONE, 1));
                        },
                        ignoredServer -> clearFixtures(),
                        DevAutoTest::planAbortInvariant),
                Scenario.plan("plan_invalid_tool_rejected",
                        ignored -> null,
                        DevAutoTest::acceptPlanInvalidTool,
                        200,
                        DevAutoTest::preparePlanInvalidTool,
                        ignoredServer -> {
                            ScriptedPlanner.instance().clear();
                            clearFixtures();
                        },
                        DevAutoTest::planRejectionInvariant),
                Scenario.plan("plan_owner_cancel",
                        ignored -> planOf("go a very long way",
                                ToolCall.of("goto").with("x", spawn.getX() + 100_000)
                                        .with("z", spawn.getZ()),
                                ToolCall.of("mine").with("resource", "dirt").with("count", 1)),
                        DevAutoTest::acceptPlanOwnerCancel,
                        400,
                        ignoredServer -> prepareInventory(),
                        ignoredServer -> clearFixtures(),
                        () -> null),
                Scenario.probe("llm_tool_schema",
                        () -> true,
                        DevAutoTest::llmSchemaViolation,
                        20,
                        ignoredServer -> {
                        },
                        ignoredServer -> {
                        },
                        () -> null),
                Scenario.probe("llm_protocol",
                        () -> true,
                        DevAutoTest::llmProtocolViolation,
                        20,
                        ignoredServer -> {
                        },
                        ignoredServer -> {
                        },
                        () -> null),
                Scenario.probe("llm_claude_tool_schema",
                        () -> true,
                        DevAutoTest::llmClaudeSchemaViolation,
                        20,
                        ignoredServer -> {
                        },
                        ignoredServer -> {
                        },
                        () -> null),
                Scenario.probe("llm_claude_protocol",
                        () -> true,
                        DevAutoTest::llmClaudeProtocolViolation,
                        20,
                        ignoredServer -> {
                        },
                        ignoredServer -> {
                        },
                        () -> null),
                Scenario.plan("llm_claude_plan",
                        ignored -> null,
                        DevAutoTest::acceptLlmClaudePlan,
                        2400,
                        ignoredServer -> prepareLlmClaudePlan(),
                        DevAutoTest::clearLlmPlanner,
                        () -> null),
                Scenario.plan("llm_plan_multi_step",
                        ignored -> null,
                        DevAutoTest::acceptLlmMultiStep,
                        2400,
                        ignoredServer -> prepareLlmMultiStep(),
                        DevAutoTest::clearLlmPlanner,
                        () -> null),
                Scenario.plan("llm_unknown_tool",
                        ignored -> null,
                        DevAutoTest::acceptLlmUnknownTool,
                        200,
                        ignoredServer -> prepareLlmPlan(FixtureTransport.of(FixtureTransport.ok(
                                llmResponse("On it.",
                                        llmContract("[]", false),
                                        llmCall("mine", "{\"resource\":\"dirt\",\"count\":1}"),
                                        llmCall("teleport", "{\"x\":0,\"z\":0}")))),
                                "take me home"),
                        DevAutoTest::clearLlmPlanner,
                        DevAutoTest::planRejectionInvariant),
                Scenario.plan("llm_http_retry",
                        ignored -> null,
                        DevAutoTest::acceptLlmHttpRetry,
                        400,
                        ignoredServer -> prepareLlmPlan(FixtureTransport.of(
                                FixtureTransport.rateLimited("0"),
                                FixtureTransport.ok(llmResponse("One moment.",
                                        llmContract("[]", false),
                                        llmCall("goto", "{\"x\":" + (spawn.getX() + 2) + ",\"z\":"
                                                + spawn.getZ() + "}")))),
                                "come here"),
                        DevAutoTest::clearLlmPlanner,
                        () -> null),
                Scenario.plan("llm_speechless_action",
                        ignored -> null,
                        DevAutoTest::acceptLlmSpeechlessAction,
                        400,
                        ignoredServer -> prepareLlmPlan(FixtureTransport.of(FixtureTransport.ok(
                                llmResponse(null,
                                        llmContract("[]", false),
                                        llmCall("goto", "{\"x\":" + (spawn.getX() + 2) + ",\"z\":"
                                                + spawn.getZ() + "}")))),
                                "come here"),
                        DevAutoTest::clearLlmPlanner,
                        () -> null),
                Scenario.probe("llm_malformed_output",
                        () -> llmPlannerSettled(),
                        () -> llmFailureVerdict("malformed arguments"),
                        200,
                        ignoredServer -> prepareLlmPlan(FixtureTransport.of(FixtureTransport.ok(
                                llmResponse("Sure.", llmCall("mine", "{not json at all")))),
                                "mine something"),
                        DevAutoTest::clearLlmPlanner,
                        DevAutoTest::planRejectionInvariant),
                Scenario.probe("llm_http_error",
                        () -> llmPlannerSettled(),
                        () -> llmHttpErrorVerdict(),
                        200,
                        ignoredServer -> prepareLlmPlan(FixtureTransport.of(FixtureTransport.error(
                                401, "invalid_api_key", "Incorrect API key provided")),
                                "mine something"),
                        DevAutoTest::clearLlmPlanner,
                        DevAutoTest::planRejectionInvariant),
                Scenario.probe("llm_timeout",
                        () -> llmPlannerSettled(),
                        () -> llmFailureVerdict("timeout"),
                        200,
                        ignoredServer -> prepareLlmPlan(FixtureTransport.hanging(), "mine something"),
                        DevAutoTest::clearLlmPlanner,
                        DevAutoTest::planRejectionInvariant),
                Scenario.probe("llm_talk_only",
                        () -> llmPlannerSettled(),
                        () -> llmFailureVerdict("missing status"),
                        200,
                        ignoredServer -> prepareLlmPlan(FixtureTransport.of(FixtureTransport.ok(
                                llmResponse("You already have plenty of dirt."))),
                                "get me some dirt"),
                        DevAutoTest::clearLlmPlanner,
                        DevAutoTest::planRejectionInvariant),
                Scenario.probe("llm_oversized_reply",
                        () -> llmPlannerSettled(),
                        DevAutoTest::llmOversizedVerdict,
                        200,
                        ignoredServer -> prepareLlmPlan(FixtureTransport.of(FixtureTransport.ok(
                                llmResponse("I have a very long route.", nineGotoCalls()))),
                                "walk the long route"),
                        DevAutoTest::clearLlmPlanner,
                        DevAutoTest::planRejectionInvariant),
                Scenario.probe("llm_stale_request",
                        () -> llmPlannerSettled() && scenarioTicks > 60,
                        DevAutoTest::llmStaleVerdict,
                        300,
                        ignoredServer -> prepareLlmPlan(FixtureTransport.delayed(400L,
                                FixtureTransport.ok(llmResponse("Coming.",
                                        llmContract("[]", false),
                                        llmCall("goto", "{\"x\":" + (spawn.getX() + 40_000)
                                                + ",\"z\":" + spawn.getZ() + "}")))),
                                "go a very long way"),
                        DevAutoTest::clearLlmPlanner,
                        DevAutoTest::planRejectionInvariant),
                Scenario.probe("llm_plan_boundary",
                        () -> scenarioTicks > 250,
                        DevAutoTest::llmPlanBoundaryVerdict,
                        400,
                        ignoredServer -> prepareLlmPlanBoundary(),
                        DevAutoTest::clearLlmPlanner,
                        () -> null),
                Scenario.probe("llm_action_then_completed",
                        () -> llmPlannerSettled() && companion.getStandingOrder() == null,
                        DevAutoTest::llmActionThenCompletedVerdict,
                        500,
                        ignoredServer -> prepareLlmActionThenCompleted(),
                        DevAutoTest::clearLlmPlanner,
                        () -> null),
                Scenario.probe("llm_replan_budget",
                        () -> journalHas(EventKind.PLANNER_BUDGET_EXHAUSTED),
                        DevAutoTest::llmReplanBudgetVerdict,
                        1400,
                        ignoredServer -> prepareLlmReplanBudget(),
                        DevAutoTest::clearLlmPlanner,
                        DevAutoTest::llmReplanBudgetInvariant),
                // The playtest semantic regressions: a delivery contract the plan does not honor
                // gets one repair; a reply that stays incomplete after its repair closes the order.
                Scenario.probe("llm_contract_repair",
                        () -> llmTransport != null && llmTransport.calls() == 2
                                && !companion.getPlanExecutor().isPlannerRequestPending(),
                        () -> llmContractRepairVerdict("does not fetch 1 iron_sword"),
                        400,
                        ignoredServer -> prepareLlmContractRepair(),
                        DevAutoTest::clearLlmPlanner,
                        () -> null),
                // One fetch_items call covering a two-deliverable contract is accepted without a
                // repair round, executes as one plan step, and really delivers both items.
                Scenario.plan("llm_fetch_items_coverage",
                        ignored -> null,
                        DevAutoTest::acceptLlmFetchItemsCoverage,
                        2400,
                        ignoredServer -> prepareLlmFetchItemsCoverage(),
                        DevAutoTest::clearLlmPlanner,
                        () -> null),
                // The single-call habit is absorbed without spending the repair: a contract-only
                // first reply stores its contract and gets a free continuation request, and the
                // follow-up plan is still validated against the whole stored contract.
                Scenario.probe("llm_contract_only_continuation",
                        () -> llmTransport != null && llmTransport.calls() == 2
                                && !companion.getPlanExecutor().isPlannerRequestPending(),
                        DevAutoTest::llmContractContinuationVerdict,
                        400,
                        ignoredServer -> prepareLlmContractOnlyContinuation(),
                        DevAutoTest::clearLlmPlanner,
                        () -> null),
                // A model that keeps sending the contract alone gets the continuation, then the
                // repair, then an honest close: three requests, nothing executed.
                Scenario.probe("llm_contract_only_exhausted",
                        () -> llmPlannerSettled() && companion.getStandingOrder() == null,
                        DevAutoTest::llmContractOnlyExhaustedVerdict,
                        300,
                        ignoredServer -> prepareLlmContractOnlyExhausted(),
                        DevAutoTest::clearLlmPlanner,
                        DevAutoTest::planRejectionInvariant),
                Scenario.probe("llm_contract_exhausted",
                        () -> llmPlannerSettled() && companion.getStandingOrder() == null,
                        DevAutoTest::llmContractExhaustedVerdict,
                        300,
                        ignoredServer -> prepareLlmContractExhausted(),
                        DevAutoTest::clearLlmPlanner,
                        DevAutoTest::planRejectionInvariant),
                Scenario.probe("llm_answered",
                        () -> llmPlannerSettled() && companion.getStandingOrder() == null,
                        () -> llmAnsweredVerdict("inventory answer"),
                        200,
                        ignoredServer -> prepareLlmPlan(FixtureTransport.of(FixtureTransport.ok(
                                llmResponse(null, llmStatus("ANSWERED",
                                        "I am carrying cobblestone and a stone pickaxe.")))),
                                "what is in your inventory?"),
                        DevAutoTest::clearLlmPlanner,
                        DevAutoTest::planRejectionInvariant),
                // Never in a profile: these are the only scenarios that leave the machine or cost
                // money. Select them by name, and only with a key configured.
                Scenario.probe("llm_live",
                        DevAutoTest::llmLiveAnswered,
                        DevAutoTest::llmLiveVerdict,
                        1200,
                        ignoredServer -> prepareLlmLive(),
                        DevAutoTest::clearLlmPlanner,
                        () -> null),
                Scenario.probe("llm_live_multi_step",
                        DevAutoTest::llmLiveMultiStepAnswered,
                        DevAutoTest::llmLiveMultiStepVerdict,
                        1200,
                        ignoredServer -> prepareLlmLiveMultiStep(),
                        DevAutoTest::clearLlmPlanner,
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
            // No profile, not even "full", may spend money or leave the machine. Live scenarios
            // are reachable by name only; in CI there is no key and they would fail on every run.
            scenarios = scenarios.stream()
                    .filter(scenario -> !LIVE_SCENARIOS.contains(scenario.name()))
                    .toList();
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
            if (hostile != null && provokeHostileEachTick && hostile.getTarget() != companion) {
                hostile.setTarget(companion);
            }
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
            String contextViolation = contextInvariant();
            if (contextViolation != null) {
                fail(server, "world context invalid: " + contextViolation);
                return;
            }
        }
        if (!advanceRequested && scenarioIndex < scenarios.size()) {
            Scenario probe = scenarios.get(scenarioIndex);
            if (probe.isProbe() && Boolean.TRUE.equals(probe.ready().get())) {
                advanceRequested = true;
            }
        }
        if (inPlanScenario() && currentPlanOutcome == null) {
            PlanOutcome outcome = companion.getPlanExecutor().lastOutcome().orElse(null);
            boolean mine = outcome != null && (currentPlan != null
                    ? outcome.planId().equals(currentPlan.id())
                    : !outcome.planId().equals(planOutcomeBaseline));
            if (mine) {
                currentPlanOutcome = outcome;
                advanceRequested = true;
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
        if ("plan_owner_cancel".equals(scenario.name()) && scenarioTicks == 40) {
            // The same shape as /companion stop: cancel the plan, not just the running goal.
            companion.getPlanExecutor().cancel(Reasons.CANCELLED_BY_OWNER);
        }
        if ("llm_stale_request".equals(scenario.name()) && scenarioTicks == 2) {
            // Well inside the fixture's 400ms delay: the request is genuinely in flight, which is
            // the only window in which a stale answer can be created.
            companion.getPlanExecutor().discardPendingPlannerRequests();
            companion.closeStandingOrder();
        }
        if (scenarioTicks > scenario.hardCapTicks()) {
            fail(server, scenario.name() + " exceeded its hard cap");
        }
    }

    private static void startScenario(MinecraftServer server) {
        Scenario scenario = scenarios.get(scenarioIndex);
        scenarioTicks = 0;
        currentGoal = null;
        currentOutcome = null;
        currentPlan = null;
        currentPlanOutcome = null;
        advanceRequested = false;
        advanceNotBeforeTick = -1;
        planOutcomeBaseline = companion.getPlanExecutor().lastOutcome()
                .map(PlanOutcome::planId).orElse("");
        scenario.setup().accept(server);
        if (scenario.isProbe()) {
            // Setup did everything; nothing is submitted and nothing is expected to be.
            MineMate.LOGGER.info("[autotest] {} START", scenario.name());
            return;
        }
        if (scenario.isPlan()) {
            currentPlan = scenario.plan().apply(companion);
            if (currentPlan != null) {
                // ACCEPTED and REJECTED are both legal starts: a rejected plan publishes its
                // outcome immediately, which is exactly what the rejection scenario asserts on.
                if (companion.getPlanExecutor().start(currentPlan) == PlanExecutor.StartResult.BUSY) {
                    fail(server, scenario.name() + " plan was refused as busy");
                    return;
                }
            }
        } else {
            currentGoal = scenario.goal().apply(companion);
            if (companion.getSupervisor().submit(currentGoal)
                    != io.github.thomas16.minemate.task.TaskSupervisor.SubmitResult.ACCEPTED) {
                fail(server, scenario.name() + " was not accepted");
                return;
            }
        }
        MineMate.LOGGER.info("[autotest] {} START", scenario.name());
    }

    private static void finishScenario(MinecraftServer server) {
        Scenario scenario = scenarios.get(scenarioIndex);
        boolean accepted;
        String rendered;
        if (scenario.isProbe()) {
            String violation = scenario.verdict().get();
            accepted = violation == null;
            rendered = violation == null ? "ok" : violation;
        } else if (scenario.isPlan()) {
            accepted = currentPlanOutcome != null && scenario.acceptsPlan().test(currentPlanOutcome);
            rendered = currentPlanOutcome == null ? "no outcome" : currentPlanOutcome.toCompactString();
        } else {
            accepted = currentOutcome != null && scenario.accepts().test(currentOutcome);
            rendered = currentOutcome == null ? "no outcome" : currentOutcome.toCompactString();
        }
        scenario.teardown().accept(server);
        if (!accepted) {
            fail(server, scenario.name() + " returned " + rendered);
            return;
        }
        MineMate.LOGGER.info("[autotest] {} PASS - {}", scenario.name(), rendered);
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

    /**
     * EntityHelper.isAngryAtPlayer requires mob.getTarget() == the companion, and a vanilla zombie
     * never targets a LivingEntity on its own. Relying on the kill aura to punch it into retaliating
     * makes the fixture depend on PlayerEngine state that no scenario resets. Set the vanilla
     * retaliation state directly: setLastHurtByMob lets HurtByTargetGoal re-acquire the companion if
     * a target goal ever drops it, and setTarget makes it true on the very first tick.
     */
    private static void provokeHostile() {
        hostile.setLastHurtByMob(companion);
        hostile.setTarget(companion);
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

    private static Plan planOf(String requestText, ToolCall... calls) {
        List<PlanStep> steps = new ArrayList<>(calls.length);
        for (ToolCall call : calls) {
            steps.add(PlanStep.of(call));
        }
        return Plan.of(requestText, steps);
    }

    private static void preparePlanMultiStep() {
        prepareStorage(0);
        insert(new ItemStack(Items.DIRT, 4));
    }

    private static boolean acceptPlanMultiStep(PlanOutcome outcome) {
        boolean accepted = outcome.status() == PlanStatus.SUCCEEDED
                && outcome.completedSteps() == 3
                && outcome.stepOutcomes().size() == 3
                && outcome.stepOutcomes().stream().allMatch(PlanStepOutcome::succeeded)
                && inventoryCount(Items.DIRT) == 4;
        if (!accepted) {
            MineMate.LOGGER.error("[autotest] plan_multi_step postcondition {} held={}",
                    outcome.toCompactString(), inventoryCount(Items.DIRT));
        }
        return accepted;
    }

    /**
     * Proves the executor abandons a plan at the first failing step. The furnace fixture is the one
     * {@code smelt_no_fuel} already relies on, so the failure itself is known-good.
     */
    private static boolean acceptPlanStepFailureAborts(PlanOutcome outcome) {
        boolean accepted = outcome.status() == PlanStatus.BLOCKED
                && (Reasons.NO_FUEL.equals(outcome.reason())
                        || Reasons.SETUP_FAILED.equals(outcome.reason()))
                && outcome.completedSteps() == 0
                && outcome.stepOutcomes().size() == 1;
        if (!accepted) {
            MineMate.LOGGER.error("[autotest] plan_step_failure_aborts postcondition {} steps={}",
                    outcome.toCompactString(), outcome.stepOutcomes().size());
        }
        return accepted;
    }

    /** The steps after the failing one must never have been submitted at all. */
    private static String planAbortInvariant() {
        if (planSawForbiddenGoal) {
            return "a step after the failing one was submitted";
        }
        companion.getSupervisor().currentGoal().ifPresent(goal -> {
            if ("mine".equals(goal.kind()) || "goto".equals(goal.kind())) {
                planSawForbiddenGoal = true;
            }
        });
        return null;
    }

    private static void preparePlanInvalidTool(MinecraftServer server) {
        prepareInventory();
        ScriptedPlanner planner = ScriptedPlanner.instance();
        planner.clear();
        planner.bind(companion);
        planner.setDelayMs(0L);
        // Raw planner JSON on purpose: this is the exact parse and validation path a stage-5
        // model's output takes, and the only headless coverage of it.
        planner.scriptRaw("bad plan", "["
                + "{\"tool\":\"mine\",\"arguments\":{\"resource\":\"dirt\",\"count\":1}},"
                + "{\"tool\":\"mine\",\"arguments\":{\"resource\":\"unobtainium\",\"count\":1}},"
                + "{\"tool\":\"goto\",\"arguments\":{\"x\":0,\"z\":0}}]");
        WorldSnapshot snapshot = companion.getContext().snapshot();
        PlannerDispatch.request(companion, planner, new PlanRequest("bad plan", snapshot,
                snapshot.render(), null, ToolRegistry.defaults().schemaJson()));
    }

    private static boolean acceptPlanInvalidTool(PlanOutcome outcome) {
        boolean accepted = outcome.status() == PlanStatus.REJECTED
                && Reasons.UNKNOWN_RESOURCE.equals(outcome.reason())
                && outcome.stepOutcomes().isEmpty()
                && outcome.completedSteps() == 0
                && ScriptedPlanner.instance().requestCount() > 0;
        if (!accepted) {
            MineMate.LOGGER.error("[autotest] plan_invalid_tool_rejected postcondition {} requests={}",
                    outcome.toCompactString(), ScriptedPlanner.instance().requestCount());
        }
        return accepted;
    }

    /** A rejected plan must not have moved the companion: nothing may ever reach the supervisor. */
    private static String planRejectionInvariant() {
        if (companion.getSupervisor().currentGoal().isPresent()) {
            planSawForbiddenGoal = true;
            return "a rejected plan still submitted a goal";
        }
        return null;
    }

    private static boolean acceptPlanOwnerCancel(PlanOutcome outcome) {
        boolean accepted = outcome.status() == PlanStatus.CANCELLED
                && Reasons.CANCELLED_BY_OWNER.equals(outcome.reason())
                && outcome.completedSteps() == 0
                && !companion.getPlanExecutor().isActive();
        if (!accepted) {
            MineMate.LOGGER.error("[autotest] plan_owner_cancel postcondition {} active={}",
                    outcome.toCompactString(), companion.getPlanExecutor().isActive());
        }
        return accepted;
    }

    // ---- stage 5: hosted planner over a fixture transport -----------------------------------
    //
    // Every scenario below runs a real request builder and a real response parser -- OpenAI for
    // the llm_* family, Anthropic for the llm_claude_* family; only the socket is replaced. None
    // of them can reach the network, and MineMate.installPlanner refuses to install a hosted
    // planner at all during an autotest run, so a developer's ambient API keys cannot change what
    // these assert.

    /** Short, so llm_timeout resolves well inside its cap rather than at the end of one. */
    private static final int LLM_TIMEOUT_SECONDS = 2;
    /** Replans allowed in llm_replan_budget. Small on purpose: each one costs ~100 ticks to observe. */
    private static final int LLM_REPLAN_BUDGET = 2;

    private static void installLlmPlanner(FixtureTransport transport, int maxReplans) {
        PlannerConfig config = PlannerConfig.of(PlannerConfig.PROVIDER_OPENAI,
                "http://fixture.invalid/v1", "fixture-model", "fixture-key", LLM_TIMEOUT_SECONDS,
                maxReplans);
        llmTransport = transport;
        Planners.install(new HostedPlanner(new OpenAiResponsesBackend(transport, config.baseUrl(),
                config.model(), config.apiKey().orElse(""), config.reasoningEffort(),
                config.verbosity(), config.maxOutputTokens()), config));
    }

    private static void installAnthropicLlmPlanner(FixtureTransport transport, int maxReplans) {
        PlannerConfig config = PlannerConfig.of(PlannerConfig.PROVIDER_ANTHROPIC,
                "http://fixture.invalid/v1", "fixture-model", "fixture-key", LLM_TIMEOUT_SECONDS,
                maxReplans);
        llmTransport = transport;
        Planners.install(new HostedPlanner(new AnthropicMessagesBackend(transport, config.baseUrl(),
                config.model(), config.apiKey().orElse(""), config.reasoningEffort(),
                config.maxOutputTokens()), config));
    }

    /**
     * The Anthropic mirror of llm_plan_multi_step, with a 529 in front: the request builder, the
     * retry loop, the error envelope, and the block-array parser all get exercised through one
     * end-to-end plan that then actually executes.
     */
    private static void prepareLlmClaudePlan() {
        prepareInventory(new ItemStack(Items.OAK_LOG, 1));
        prepareFakeOwner(CLAUDE_PLAN_OWNER_UUID, "MineMateClaudeOwner");
        installAnthropicLlmPlanner(FixtureTransport.of(
                FixtureTransport.anthropicError(529, "overloaded_error", "Overloaded"),
                FixtureTransport.ok(claudeResponse("I will make the planks, then bring them to you.",
                        claudeContract("[{\"item\":\"oak_planks\",\"count\":4}]", true),
                        claudeCall("craft", "{\"item\":\"oak_planks\",\"count\":4}"),
                        claudeCall("fetch", "{\"resource\":\"oak_planks\",\"count\":4}")))), 0);
        requestLlmPlan("make 4 oak planks for me");
    }

    /** Installs a planner with no replan budget, opens an order, and asks for a plan. */
    private static void prepareLlmPlan(FixtureTransport transport, String requestText) {
        prepareInventory();
        installLlmPlanner(transport, 0);
        requestLlmPlan(requestText);
    }

    /** Craft leaves the output with the companion; the dependent fetch then delivers that output. */
    private static void prepareLlmMultiStep() {
        prepareInventory(new ItemStack(Items.OAK_LOG, 1));
        prepareFakeOwner(FAKE_OWNER_UUID, "MineMateOwner");
        installLlmPlanner(FixtureTransport.of(FixtureTransport.ok(
                llmResponse("I will make the planks, then bring them to you.",
                        llmContract("[{\"item\":\"oak_planks\",\"count\":4}]", true),
                        llmCall("craft", "{\"item\":\"oak_planks\",\"count\":4}"),
                        llmCall("fetch", "{\"resource\":\"oak_planks\",\"count\":4}")))), 0);
        requestLlmPlan("make 4 oak planks for me");
    }

    private static void requestLlmPlan(String requestText) {
        // Explicitly, not incidentally: several verdicts below turn on whether a PLANNER_FAILED
        // event exists, and llm_timeout legitimately leaves one behind. Without this the meaning of
        // "no planner failure" would depend on which scenario ran before, which is exactly the
        // order-sensitivity this suite already has one known case of.
        companion.getJournal().clear();
        companion.openStandingOrder(requestText);
        WorldSnapshot snapshot = companion.getContext().snapshot();
        PlannerDispatch.request(companion, Planners.active(), new PlanRequest(requestText, snapshot,
                snapshot.render(), null, ToolRegistry.defaults().schemaJson()));
    }

    private static void prepareLlmReplanBudget() {
        prepareInventory();
        llmBudgetSawFinalPlanOpen = false;
        // The repeating reply re-declares the contract on every replan, which dispatch tolerates
        // and ignores; this scenario doubles as coverage of that path.
        installLlmPlanner(FixtureTransport.of(FixtureTransport.ok(llmResponse("Working on it.",
                llmContract("[]", false),
                llmCall("goto", "{\"x\":" + (spawn.getX() + 2) + ",\"z\":" + spawn.getZ() + "}")))),
                LLM_REPLAN_BUDGET);
        requestLlmPlan("keep busy");
    }

    private static void prepareLlmPlanBoundary() {
        prepareInventory();
        // The contract goes with the first reply only; the replan reply deliberately omits it,
        // proving a stored contract is not demanded again.
        installLlmPlanner(FixtureTransport.of(
                FixtureTransport.ok(llmResponse("I am moving into position.",
                        llmContract("[]", false),
                        llmCall("goto", "{\"x\":" + (spawn.getX() + 2) + ",\"z\":"
                                + spawn.getZ() + "}"))),
                FixtureTransport.ok(llmResponse("I am continuing to the distant checkpoint.",
                        llmCall("goto", "{\"x\":" + (spawn.getX() + 40_000) + ",\"z\":"
                                + spawn.getZ() + "}")))), 3);
        requestLlmPlan("move through both checkpoints");
    }

    private static void prepareLlmActionThenCompleted() {
        prepareInventory();
        prepareFakeOwner(FAKE_OWNER_UUID, "MineMateOwner");
        installLlmPlanner(FixtureTransport.of(
                FixtureTransport.ok(llmResponse("I am coming over.",
                        llmContract("[]", false),
                        llmCall("goto", "{\"x\":" + (spawn.getX() + 2) + ",\"z\":"
                                + spawn.getZ() + "}"))),
                FixtureTransport.ok(llmResponse(null,
                        llmStatus("COMPLETED", "I am here.")))), 3);
        requestLlmPlan("come here");
    }

    /**
     * The iron-sword playtest shape: the first reply declares delivery but only crafts, so the
     * dispatcher demands one repair; the repaired reply adds the fetch and is accepted.
     */
    private static void prepareLlmContractRepair() {
        prepareInventory();
        prepareFakeOwner(FAKE_OWNER_UUID, "MineMateOwner");
        installLlmPlanner(FixtureTransport.of(
                FixtureTransport.ok(llmResponse("I will forge you an iron sword.",
                        llmContract("[{\"item\":\"iron_sword\",\"count\":1}]", true),
                        llmCall("craft", "{\"item\":\"iron_sword\",\"count\":1}"))),
                FixtureTransport.ok(llmResponse("I will forge the sword and bring it over.",
                        llmContract("[{\"item\":\"iron_sword\",\"count\":1}]", true),
                        llmCall("craft", "{\"item\":\"iron_sword\",\"count\":1}"),
                        llmCall("fetch", "{\"resource\":\"iron_sword\",\"count\":1}")))), 0);
        requestLlmPlan("make me an iron sword");
    }

    /**
     * The multi-item shape fetch_items exists for: one call covers a two-deliverable contract, so
     * coverage passes without a repair round and the whole order is one plan step. Both items are
     * preloaded, keeping the scenario to sequential delivery time.
     */
    private static void prepareLlmFetchItemsCoverage() {
        prepareInventory(new ItemStack(Items.OAK_PLANKS, 2), new ItemStack(Items.STICK, 1));
        prepareFakeOwner(FETCH_ITEMS_LLM_OWNER_UUID, "MineMateFetchItemsLlmOwner");
        installLlmPlanner(FixtureTransport.of(FixtureTransport.ok(
                llmResponse("Bringing you the planks and the stick.",
                        llmContract("[{\"item\":\"oak_planks\",\"count\":2},"
                                + "{\"item\":\"stick\",\"count\":1}]", true),
                        llmCall("fetch_items", "{\"items\":[{\"item\":\"oak_planks\",\"count\":2},"
                                + "{\"item\":\"stick\",\"count\":1}]}")))), 0);
        requestLlmPlan("bring me two oak planks and a stick");
    }

    /**
     * The live bucket regression: a single-call-habit model declares the contract, says it is on
     * the job, and plans nothing. The contract is stored and a free continuation asks for the
     * work; the follow-up deliberately omits the contract, so its coverage is judged against the
     * stored one through the awaiting-coverage window.
     */
    private static void prepareLlmContractOnlyContinuation() {
        prepareInventory();
        prepareFakeOwner(FAKE_OWNER_UUID, "MineMateOwner");
        installLlmPlanner(FixtureTransport.of(
                FixtureTransport.ok(llmResponse("I will get you an iron sword.",
                        llmContract("[{\"item\":\"iron_sword\",\"count\":1}]", true))),
                FixtureTransport.ok(llmResponse("Forging the sword and bringing it over.",
                        llmCall("craft", "{\"item\":\"iron_sword\",\"count\":1}"),
                        llmCall("fetch", "{\"resource\":\"iron_sword\",\"count\":1}")))), 0);
        requestLlmPlan("can u make me an iron sword");
    }

    /**
     * A model that never gets past the habit: every reply is the same contract-only shape, so the
     * order spends the continuation, then the repair, then closes honestly after three requests.
     */
    private static void prepareLlmContractOnlyExhausted() {
        prepareInventory();
        prepareFakeOwner(FAKE_OWNER_UUID, "MineMateOwner");
        installLlmPlanner(FixtureTransport.of(
                FixtureTransport.ok(llmResponse("I will get you an iron sword.",
                        llmContract("[{\"item\":\"iron_sword\",\"count\":1}]", true)))), 0);
        requestLlmPlan("can u make me an iron sword");
    }

    /**
     * The stone-tools playtest shape, exercising both violation kinds: the first reply declares no
     * contract at all, and the repaired reply declares one its stick-crafting still does not cover,
     * so the order closes without executing anything.
     */
    private static void prepareLlmContractExhausted() {
        prepareInventory();
        installLlmPlanner(FixtureTransport.of(
                FixtureTransport.ok(llmResponse("I will get a stone tool set going.",
                        llmCall("craft", "{\"item\":\"stick\",\"count\":7}"))),
                FixtureTransport.ok(llmResponse("Finishing the tool set.",
                        llmContract("[{\"item\":\"stone_pickaxe\",\"count\":1},"
                                + "{\"item\":\"stone_axe\",\"count\":1},"
                                + "{\"item\":\"stone_shovel\",\"count\":1},"
                                + "{\"item\":\"stone_sword\",\"count\":1},"
                                + "{\"item\":\"stone_hoe\",\"count\":1}]", true),
                        llmCall("craft", "{\"item\":\"stick\",\"count\":8}")))), 0);
        requestLlmPlan("give me a full set of stone tools");
    }

    private static void clearLlmPlanner(MinecraftServer server) {
        if (companion != null && companion.getPlanExecutor() != null) {
            companion.getPlanExecutor().discardPendingPlannerRequests();
            if (companion.getPlanExecutor().isActive()) {
                companion.getPlanExecutor().cancel(Reasons.CANCELLED_BY_OWNER);
            }
            companion.closeStandingOrder();
        }
        Planners.install(ScriptedPlanner.instance());
        llmTransport = null;
        clearFakeOwner();
        clearFixtures();
    }

    /** True once the planner has answered, one way or another, and left the executor idle. */
    private static boolean llmPlannerSettled() {
        PlanExecutor executor = companion.getPlanExecutor();
        return !executor.isPlannerRequestPending() && !executor.isActive();
    }

    /**
     * Shared setup for opt-in scenarios that talk to the real endpoint, so the model id, strict
     * schema and reply shape are checked against the provider. They charge a real account and are
     * therefore in no profile.
     */
    private static void prepareLlmLive() {
        prepareLlmLive(LLM_LIVE_ORDER, "llm_live");
    }

    private static void prepareLlmLiveMultiStep() {
        prepareLlmLive(LLM_LIVE_MULTI_STEP_ORDER, "llm_live_multi_step");
    }

    private static void prepareLlmLive(String order, String scenarioName) {
        prepareInventory();
        // With no owner present, anything the model plausibly plans for "bring me X" binds to
        // owner_offline before it runs, and the test proves only that the JSON parsed.
        prepareFakeOwner(FAKE_OWNER_UUID, "MineMateOwner");
        PlannerConfig file = PlannerConfig.loadOrCreate();
        // No replan budget: one order must cost exactly one request. The provider comes from the
        // config file, so the same pair of live scenarios checks whichever backend is configured.
        PlannerConfig config = PlannerConfig.of(file.provider(), file.baseUrl(), file.model(),
                file.apiKey().orElse(""), 60, 0);
        MineMate.LOGGER.info("[autotest] {} against {}", scenarioName, config.describe());
        llmTransport = null;
        llmObservedLivePlan = null;
        Planners.install(HostedPlanner.of(config));
        companion.getJournal().clear();
        companion.openStandingOrder(order);
        WorldSnapshot snapshot = companion.getContext().snapshot();
        PlannerDispatch.request(companion, Planners.active(), new PlanRequest(order,
                snapshot, snapshot.render(), null, ToolRegistry.defaults().schemaJson()));
    }

    private static final String LLM_LIVE_ORDER = "get me 2 dirt and then come stand next to me";
    private static final String LLM_LIVE_MULTI_STEP_ORDER =
            "Make me an oak boat and then a chest, and bring both to me.";

    /** Names the environment variable that matches the configured provider, or null with a key. */
    private static String llmLiveKeyMissing() {
        PlannerConfig file = PlannerConfig.loadOrCreate();
        if (file.apiKey().isPresent()) {
            return null;
        }
        String env = PlannerConfig.PROVIDER_OPENAI.equals(file.provider())
                ? "OPENAI_API_KEY" : "ANTHROPIC_API_KEY";
        return "no API key configured; set " + env + " or -Dminemate.planner.apiKey";
    }

    /** The model has answered once the request is no longer pending and something came of it. */
    private static boolean llmLiveAnswered() {
        PlanExecutor executor = companion.getPlanExecutor();
        return !executor.isPlannerRequestPending()
                && (executor.isActive() || sawNewPlanOutcome()
                        || journalHas(EventKind.PLANNER_FAILED)
                        || companion.getStandingOrder() == null);
    }

    private static boolean llmLiveMultiStepAnswered() {
        PlanExecutor executor = companion.getPlanExecutor();
        if (executor.isPlannerRequestPending()) {
            return false;
        }
        executor.currentPlan().ifPresent(plan -> llmObservedLivePlan = plan);
        return llmObservedLivePlan != null || sawNewPlanOutcome()
                || journalHas(EventKind.PLANNER_FAILED)
                || companion.getStandingOrder() == null;
    }

    /**
     * Scoped to the planner integration and nothing downstream of it: the model id and endpoint are
     * right, the strict schema was accepted, the reply parsed, and the resulting calls survived
     * validation and reached the supervisor.
     *
     * <p>It deliberately stops short of asserting that the plan then succeeds. Whether a given
     * deterministic skill can finish in a headless superflat world is a stage-6 question, and tying
     * a live API check to it would mean this test fails for reasons that have nothing to do with
     * the planner. That is not hypothetical: the model reasonably chose {@code fetch}, which is
     * currently unable to make progress on non-craftable resources.
     */
    private static String llmLiveVerdict() {
        String keyMissing = llmLiveKeyMissing();
        if (keyMissing != null) {
            return keyMissing;
        }
        if (journalHas(EventKind.PLANNER_FAILED)) {
            return "the live planner failed; the request and reply bodies are in logs/debug.log";
        }
        if (companion.getPlanExecutor().isActive()) {
            MineMate.LOGGER.info("[autotest] llm_live plan accepted: {}",
                    companion.getPlanExecutor().currentPlan()
                            .map(plan -> "\"" + plan.dialogue() + "\" / " + plan.steps().size()
                                    + " steps: " + plan.steps().stream()
                                            .map(step -> step.call().toCompactString()).toList())
                            .orElse("?"));
            return null;
        }
        Optional<PlanOutcome> outcome = companion.getPlanExecutor().lastOutcome()
                .filter(published -> !published.planId().equals(planOutcomeBaseline));
        if (outcome.isEmpty()) {
            return "the model answered with no tool calls for: " + LLM_LIVE_ORDER;
        }
        MineMate.LOGGER.info("[autotest] llm_live outcome {}", outcome.get().toCompactString());
        for (PlanStepOutcome step : outcome.get().stepOutcomes()) {
            MineMate.LOGGER.info("[autotest] llm_live step {} {} -> {} {}", step.index(),
                    step.call().toCompactString(), step.status(), step.reason());
        }
        if (outcome.get().status() == PlanStatus.REJECTED) {
            return "the model produced a plan that failed validation: " + outcome.get().reason();
        }
        return null;
    }

    // ---- fixture bodies ----------------------------------------------------------------------

    /**
     * A Responses reply, built the way the real one arrives: a reasoning item first, so a parser
     * that indexes {@code output[0]} instead of filtering by type fails here rather than in
     * production.
     */
    private static String llmResponse(String text, JsonObject... calls) {
        JsonArray output = new JsonArray();
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("type", "reasoning");
        reasoning.addProperty("id", "rs_fixture");
        reasoning.add("summary", new JsonArray());
        output.add(reasoning);
        if (text != null) {
            JsonObject part = new JsonObject();
            part.addProperty("type", "output_text");
            part.addProperty("text", text);
            JsonArray content = new JsonArray();
            content.add(part);
            JsonObject message = new JsonObject();
            message.addProperty("type", "message");
            message.addProperty("role", "assistant");
            message.add("content", content);
            output.add(message);
        }
        for (JsonObject call : calls) {
            output.add(call);
        }
        JsonObject root = new JsonObject();
        root.addProperty("id", "resp_fixture");
        root.add("output", output);
        return root.toString();
    }

    /** Arguments go on the wire as an escaped JSON string, not a nested object. */
    private static JsonObject llmCall(String name, String argumentsJson) {
        JsonObject call = new JsonObject();
        call.addProperty("type", "function_call");
        call.addProperty("call_id", "call_" + name);
        call.addProperty("name", name);
        call.addProperty("arguments", argumentsJson);
        return call;
    }

    private static JsonObject llmStatus(String status, String message) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("status", status);
        arguments.addProperty("message", message);
        return llmCall(PlannerProtocol.STATUS_TOOL, arguments.toString());
    }

    /** deliverablesJson is the raw array, e.g. {@code [{"item":"iron_sword","count":1}]}. */
    private static JsonObject llmContract(String deliverablesJson, boolean deliverToOwner) {
        return llmCall(PlannerProtocol.CONTRACT_TOOL, "{\"deliverables\":" + deliverablesJson
                + ",\"deliver_to_owner\":" + deliverToOwner + "}");
    }

    private static JsonObject[] nineGotoCalls() {
        JsonObject[] calls = new JsonObject[Plan.MAX_STEPS + 1];
        for (int index = 0; index < calls.length; index++) {
            calls[index] = llmCall("goto", "{\"x\":" + (index + 1) + ",\"z\":0}");
        }
        return calls;
    }

    /**
     * An Anthropic Messages reply, built the way the real one arrives: a thinking block first, so
     * a parser that indexes {@code content[0]} instead of filtering by type fails here rather than
     * in production.
     */
    private static String claudeResponse(String text, JsonObject... blocks) {
        return claudeResponse("tool_use", text, blocks);
    }

    private static String claudeResponse(String stopReason, String text, JsonObject... blocks) {
        JsonArray content = new JsonArray();
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", "thinking");
        thinking.addProperty("thinking", "Considering the order.");
        thinking.addProperty("signature", "sig_fixture");
        content.add(thinking);
        if (text != null) {
            content.add(claudeText(text));
        }
        for (JsonObject block : blocks) {
            content.add(block);
        }
        JsonObject root = new JsonObject();
        root.addProperty("id", "msg_fixture");
        root.addProperty("type", "message");
        root.addProperty("role", "assistant");
        root.add("content", content);
        root.addProperty("stop_reason", stopReason);
        return root.toString();
    }

    private static JsonObject claudeText(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        return block;
    }

    /** Unlike OpenAI, {@code input} goes on the wire as a real JSON object, not an escaped string. */
    private static JsonObject claudeCall(String name, String inputJson) {
        JsonObject call = new JsonObject();
        call.addProperty("type", "tool_use");
        call.addProperty("id", "toolu_" + name);
        call.addProperty("name", name);
        call.add("input", JsonParser.parseString(inputJson));
        return call;
    }

    private static JsonObject claudeStatus(String status, String message) {
        return claudeCall(PlannerProtocol.STATUS_TOOL,
                "{\"status\":\"" + status + "\",\"message\":\"" + message + "\"}");
    }

    /** deliverablesJson is the raw array, e.g. {@code [{"item":"iron_sword","count":1}]}. */
    private static JsonObject claudeContract(String deliverablesJson, boolean deliverToOwner) {
        return claudeCall(PlannerProtocol.CONTRACT_TOOL, "{\"deliverables\":" + deliverablesJson
                + ",\"deliver_to_owner\":" + deliverToOwner + "}");
    }

    // ---- verdicts ----------------------------------------------------------------------------

    /**
     * The shared shape of every planner failure: the owner is told, and absolutely nothing else
     * happened. A failed planner that still moved the companion would be far worse than one that
     * simply did not answer.
     */
    private static String llmFailureVerdict(String label) {
        if (companion.getPlanExecutor().isActive()) {
            return label + ": a plan started anyway";
        }
        if (companion.getPlanExecutor().isPlannerRequestPending()) {
            return label + ": the request is still pending";
        }
        if (companion.getSupervisor().currentGoal().isPresent()) {
            return label + ": a goal was submitted";
        }
        if (!journalHas(EventKind.PLANNER_FAILED)) {
            return label + ": no planner_failed event was recorded";
        }
        if (companion.getStandingOrder() != null) {
            return label + ": the failed planner left an inert standing order open";
        }
        return null;
    }

    private static String llmHttpErrorVerdict() {
        String failure = llmFailureVerdict("http error");
        if (failure != null) {
            return failure;
        }
        if (llmTransport.calls() != 1) {
            return "a 401 was retried: " + llmTransport.calls() + " requests";
        }
        return null;
    }

    private static String llmStaleVerdict() {
        if (companion.getPlanExecutor().isActive()) {
            return "a discarded request still started a plan";
        }
        if (companion.getSupervisor().currentGoal().isPresent()) {
            return "a discarded request still submitted a goal";
        }
        if (sawNewPlanOutcome()) {
            return "a discarded request still published a plan outcome";
        }
        if (llmTransport.calls() != 1) {
            return "expected one request, saw " + llmTransport.calls();
        }
        return null;
    }

    /** Checks only the first hosted reply, before PlayerEngine execution can affect acceptance. */
    private static String llmLiveMultiStepVerdict() {
        String keyMissing = llmLiveKeyMissing();
        if (keyMissing != null) {
            return keyMissing;
        }
        if (journalHas(EventKind.PLANNER_FAILED)) {
            return "the live multi-step planner failed; inspect logs/debug.log";
        }
        Plan plan = llmObservedLivePlan;
        if (plan == null) {
            return "the live reply did not produce an inspectable action plan";
        }
        if (plan.disposition() != PlanDisposition.ACTION || plan.steps().size() != 2) {
            return "expected one two-action reply, got " + plan.disposition() + " with "
                    + plan.steps().size() + " actions";
        }
        ToolCall first = plan.steps().get(0).call();
        ToolCall second = plan.steps().get(1).call();
        if (!"fetch".equals(first.tool()) || !"oak_boat".equals(argument(first, "resource"))) {
            return "first action was not fetch(oak_boat): " + first.toCompactString();
        }
        if (!"fetch".equals(second.tool()) || !"chest".equals(argument(second, "resource"))) {
            return "second action was not fetch(chest): " + second.toCompactString();
        }
        if (plan.contract() == null) {
            return "the live reply did not declare an order contract";
        }
        if (!plan.contract().deliverToOwner()
                || plan.contract().deliverables().stream()
                        .noneMatch(deliverable -> "oak_boat".equals(deliverable.item()))
                || plan.contract().deliverables().stream()
                        .noneMatch(deliverable -> "chest".equals(deliverable.item()))) {
            return "the live contract did not cover oak_boat and chest for the owner: "
                    + plan.contract();
        }
        MineMate.LOGGER.info("[autotest] llm_live_multi_step accepted first reply: {}",
                plan.steps().stream().map(step -> step.call().toCompactString()).toList());
        return null;
    }

    private static String argument(ToolCall call, String name) {
        JsonElement value = call.arguments().get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static String llmOversizedVerdict() {
        String failure = llmFailureVerdict("oversized reply");
        if (failure != null) {
            return failure;
        }
        if (sawNewPlanOutcome()) {
            return "the oversized reply reached PlanExecutor";
        }
        if (llmTransport.calls() != 1) {
            return "expected one oversized request, saw " + llmTransport.calls();
        }
        return null;
    }

    private static String llmPlanBoundaryVerdict() {
        PlanExecutor executor = companion.getPlanExecutor();
        if (llmTransport.calls() != 2) {
            return "one plan boundary produced " + llmTransport.calls() + " requests instead of two";
        }
        if (!executor.isActive()) {
            return "the follow-up plan was not still active";
        }
        StandingOrder order = companion.getStandingOrder();
        if (order == null) {
            return "a delayed trigger closed the order during its follow-up plan";
        }
        if (order.replansUsed() != 1) {
            return "one plan boundary consumed " + order.replansUsed() + " replans";
        }
        if (journalHas(EventKind.PLANNER_BUDGET_EXHAUSTED)) {
            return "a delayed trigger inspected the budget during an active plan";
        }
        return null;
    }

    private static String llmActionThenCompletedVerdict() {
        if (llmTransport.calls() != 2) {
            return "expected one action and one completion request, saw " + llmTransport.calls();
        }
        if (journalHas(EventKind.PLANNER_FAILED)) {
            return "the action/completion exchange was treated as a planner failure";
        }
        Optional<PlanOutcome> outcome = companion.getPlanExecutor().lastOutcome()
                .filter(published -> !published.planId().equals(planOutcomeBaseline));
        if (outcome.isEmpty() || outcome.get().status() != PlanStatus.SUCCEEDED
                || outcome.get().completedSteps() != 1) {
            return "the initial action plan did not execute successfully";
        }
        if (companion.getStandingOrder() != null) {
            return "COMPLETED did not close the standing order";
        }
        return null;
    }

    private static String llmContractRepairVerdict(String expectedViolation) {
        if (llmTransport.calls() != 2) {
            return "expected one rejection and one repair request, saw " + llmTransport.calls();
        }
        String body = llmTransport.lastRequestBody();
        if (!body.contains("REPAIR:") || !body.contains(expectedViolation)) {
            return "the repair request did not quote the violation: " + expectedViolation;
        }
        if (journalHas(EventKind.PLANNER_FAILED)) {
            return "a repairable reply was recorded as a planner failure";
        }
        if (!companion.getPlanExecutor().isActive()) {
            return "the repaired plan was not accepted and active";
        }
        StandingOrder order = companion.getStandingOrder();
        if (order == null) {
            return "the repair closed the standing order";
        }
        OrderContract contract = order.contract();
        if (contract == null || !contract.deliverToOwner()
                || contract.deliverables().size() != 1
                || !"iron_sword".equals(contract.deliverables().get(0).item())) {
            return "the accepted repair did not store its contract: " + contract;
        }
        if (order.replansUsed() != 0) {
            return "the repair was charged against the replan budget";
        }
        return null;
    }

    private static String llmContractContinuationVerdict() {
        if (llmTransport.calls() != 2) {
            return "expected one contract-only reply and one continuation request, saw "
                    + llmTransport.calls();
        }
        String body = llmTransport.lastRequestBody();
        if (!body.contains("REPAIR:") || !body.contains("now recorded")) {
            return "the continuation request did not say the contract was recorded";
        }
        if (journalHas(EventKind.PLANNER_FAILED)) {
            return "a contract-only first reply was recorded as a planner failure";
        }
        if (!companion.getPlanExecutor().isActive()) {
            return "the continuation plan was not accepted and active";
        }
        StandingOrder order = companion.getStandingOrder();
        if (order == null) {
            return "the continuation closed the standing order";
        }
        OrderContract contract = order.contract();
        if (contract == null || !contract.deliverToOwner()
                || contract.deliverables().size() != 1
                || !"iron_sword".equals(contract.deliverables().get(0).item())) {
            return "the contract-only reply's contract was not stored: " + contract;
        }
        if (!order.repairAvailable()) {
            return "the continuation consumed the repair";
        }
        if (order.awaitingCoverage()) {
            return "an accepted covered plan left the order awaiting coverage";
        }
        if (order.replansUsed() != 0) {
            return "the continuation was charged against the replan budget";
        }
        return null;
    }

    private static String llmContractOnlyExhaustedVerdict() {
        if (llmTransport.calls() != 3) {
            return "expected continuation, repair, then close, saw " + llmTransport.calls()
                    + " requests";
        }
        if (!journalHasSummary(EventKind.PLANNER_FAILED,
                "planning failed (the reply declared a contract but no world-tool calls)")) {
            return "the exhausted continuation did not record the contract-only violation";
        }
        if (companion.getPlanExecutor().isActive()) {
            return "a contract-only order executed anyway";
        }
        if (sawNewPlanOutcome()) {
            return "a contract-only order reached PlanExecutor";
        }
        if (companion.getSupervisor().currentGoal().isPresent()) {
            return "a contract-only order submitted a goal";
        }
        if (companion.getStandingOrder() != null) {
            return "the exhausted continuation left the standing order open";
        }
        return null;
    }

    private static String llmContractExhaustedVerdict() {
        if (llmTransport.calls() != 2) {
            return "expected one rejection and one repair request, saw " + llmTransport.calls();
        }
        String body = llmTransport.lastRequestBody();
        if (!body.contains("REPAIR:") || !body.contains("did not declare the order contract")) {
            return "the repair request did not quote the missing-contract violation";
        }
        if (!journalHasSummary(EventKind.PLANNER_FAILED, "planning failed (the plan does not fetch"
                + " 1 stone_pickaxe for the owner; fetch acquires or crafts and then delivers)")) {
            return "the exhausted repair did not record the coverage violation";
        }
        if (companion.getPlanExecutor().isActive()) {
            return "an incomplete plan executed anyway";
        }
        if (sawNewPlanOutcome()) {
            return "an incomplete plan reached PlanExecutor";
        }
        if (companion.getSupervisor().currentGoal().isPresent()) {
            return "an incomplete plan submitted a goal";
        }
        if (companion.getStandingOrder() != null) {
            return "the exhausted repair left the standing order open";
        }
        return null;
    }

    /** ANSWERED closes an informational or social order without touching the world. */
    private static String llmAnsweredVerdict(String label) {
        if (llmTransport.calls() != 1) {
            return label + ": expected one request, saw " + llmTransport.calls();
        }
        if (journalHas(EventKind.PLANNER_FAILED)) {
            return label + ": an ANSWERED terminal was recorded as a planner failure";
        }
        if (sawNewPlanOutcome()) {
            return label + ": an ANSWERED terminal reached PlanExecutor";
        }
        if (companion.getSupervisor().currentGoal().isPresent()) {
            return label + ": an ANSWERED terminal submitted a goal";
        }
        if (companion.getPlanExecutor().isActive()) {
            return label + ": an ANSWERED terminal started a plan";
        }
        if (companion.getStandingOrder() != null) {
            return label + ": ANSWERED did not close the standing order";
        }
        return null;
    }

    private static String llmReplanBudgetInvariant() {
        int finalRequest = 1 + LLM_REPLAN_BUDGET;
        PlanExecutor executor = companion.getPlanExecutor();
        if (llmTransport != null && llmTransport.calls() == finalRequest && executor.isActive()) {
            if (companion.getStandingOrder() == null) {
                return "the standing order closed during the final active plan";
            }
            llmBudgetSawFinalPlanOpen = true;
        }
        if (executor.isActive() && companion.getStandingOrder() == null) {
            return "an active plan lost its standing order";
        }
        return null;
    }

    private static String llmReplanBudgetVerdict() {
        int expected = 1 + LLM_REPLAN_BUDGET;
        if (llmTransport.calls() != expected) {
            return "expected " + expected + " planner requests, saw " + llmTransport.calls();
        }
        if (companion.getStandingOrder() != null) {
            return "the standing order outlived its replan budget";
        }
        if (!llmBudgetSawFinalPlanOpen) {
            return "the test never observed the order open during the final active plan";
        }
        if (!journalHasSummary(EventKind.PLANNER_BUDGET_EXHAUSTED,
                PlannerAutonomy.BUDGET_EXHAUSTED_MESSAGE)) {
            return "budget exhaustion did not record the owner message";
        }
        return null;
    }

    private static boolean sawNewPlanOutcome() {
        return companion.getPlanExecutor().lastOutcome()
                .filter(outcome -> !outcome.planId().equals(planOutcomeBaseline))
                .isPresent();
    }

    private static boolean journalHas(EventKind kind) {
        for (JournalEntry entry : companion.getJournal().recent(CompanionJournal.CAPACITY)) {
            if (entry.kind() == kind) {
                return true;
            }
        }
        return false;
    }

    private static boolean journalHasSummary(EventKind kind, String summary) {
        for (JournalEntry entry : companion.getJournal().recent(CompanionJournal.CAPACITY)) {
            if (entry.kind() == kind && summary.equals(entry.summary())) {
                return true;
            }
        }
        return false;
    }

    // ---- plan-shaped acceptors ---------------------------------------------------------------

    private static boolean acceptLlmMultiStep(PlanOutcome outcome) {
        String body = llmTransport == null ? "" : llmTransport.lastRequestBody();
        boolean accepted = outcome.status() == PlanStatus.SUCCEEDED
                && outcome.completedSteps() == 2
                && outcome.totalSteps() == 2
                && outcome.stepOutcomes().size() == 2
                && outcome.stepOutcomes().get(0).index() == 0
                && "craft".equals(outcome.stepOutcomes().get(0).call().tool())
                && outcome.stepOutcomes().get(1).index() == 1
                && "fetch".equals(outcome.stepOutcomes().get(1).call().tool())
                // Several tool calls in one reply is what a multi-step plan is made of.
                && body.contains("\"parallel_tool_calls\":true")
                // Every valid reply carries a function call, so the provider can enforce that.
                && body.contains("\"tool_choice\":\"required\"")
                && body.contains(PlannerProtocol.STATUS_TOOL)
                && body.contains(PlannerProtocol.CONTRACT_TOOL)
                && body.contains("Include every step that is currently known")
                && !body.contains("do not try to plan the whole job")
                // The nested Chat Completions wrapper here would be rejected by the real endpoint.
                && !body.contains("\"function\":{");
        if (!accepted) {
            MineMate.LOGGER.error("[autotest] llm_plan_multi_step postcondition {}",
                    outcome.toCompactString());
        }
        return accepted;
    }

    private static boolean acceptLlmClaudePlan(PlanOutcome outcome) {
        String body = llmTransport == null ? "" : llmTransport.lastRequestBody();
        Map<String, String> headers = llmTransport == null ? Map.of()
                : llmTransport.lastRequestHeaders();
        boolean accepted = outcome.status() == PlanStatus.SUCCEEDED
                && outcome.completedSteps() == 2
                && outcome.totalSteps() == 2
                && outcome.stepOutcomes().size() == 2
                && outcome.stepOutcomes().get(0).index() == 0
                && "craft".equals(outcome.stepOutcomes().get(0).call().tool())
                && outcome.stepOutcomes().get(1).index() == 1
                && "fetch".equals(outcome.stepOutcomes().get(1).call().tool())
                // The 529 in front was retried once rather than failing the order.
                && llmTransport != null && llmTransport.calls() == 2
                // "auto", never "any": forced tool use suppresses the assistant speech the
                // protocol requires. Parallel tool use is Anthropic's default, so the OpenAI knob
                // must not leak onto this wire.
                && body.contains("\"tool_choice\":{\"type\":\"auto\"}")
                && !body.contains("\"parallel_tool_calls\"")
                // The explicit cache breakpoint on the system block, and the schema verbatim.
                && body.contains("\"cache_control\":{\"type\":\"ephemeral\"}")
                && body.contains("\"input_schema\"")
                && body.contains(PlannerProtocol.STATUS_TOOL)
                && body.contains(PlannerProtocol.CONTRACT_TOOL)
                && body.contains("Include every step that is currently known")
                && "2023-06-01".equals(headers.get("anthropic-version"))
                && headers.containsKey("x-api-key")
                && !headers.containsKey("Authorization");
        if (!accepted) {
            MineMate.LOGGER.error("[autotest] llm_claude_plan postcondition {} requests={}",
                    outcome.toCompactString(), llmTransport == null ? -1 : llmTransport.calls());
        }
        return accepted;
    }

    private static boolean acceptLlmFetchItemsCoverage(PlanOutcome outcome) {
        int planks = fakeOwnerInventoryCount(Items.OAK_PLANKS);
        int sticks = fakeOwnerInventoryCount(Items.STICK);
        boolean accepted = outcome.status() == PlanStatus.SUCCEEDED
                && outcome.completedSteps() == 1
                && outcome.totalSteps() == 1
                && outcome.stepOutcomes().size() == 1
                && "fetch_items".equals(outcome.stepOutcomes().get(0).call().tool())
                // One request: the single fetch_items call satisfied coverage with no repair round.
                && llmTransport != null && llmTransport.calls() == 1
                && planks >= 2 && sticks >= 1;
        if (!accepted) {
            MineMate.LOGGER.error(
                    "[autotest] llm_fetch_items_coverage postcondition {} requests={} planks={} sticks={}",
                    outcome.toCompactString(), llmTransport == null ? -1 : llmTransport.calls(),
                    planks, sticks);
        }
        return accepted;
    }

    private static boolean acceptLlmUnknownTool(PlanOutcome outcome) {
        boolean accepted = outcome.status() == PlanStatus.REJECTED
                && Reasons.UNKNOWN_TOOL.equals(outcome.reason())
                && outcome.stepOutcomes().isEmpty()
                && outcome.completedSteps() == 0;
        if (!accepted) {
            MineMate.LOGGER.error("[autotest] llm_unknown_tool postcondition {}",
                    outcome.toCompactString());
        }
        return accepted;
    }

    private static boolean acceptLlmHttpRetry(PlanOutcome outcome) {
        boolean accepted = outcome.status() == PlanStatus.SUCCEEDED
                && llmTransport.calls() == 2;
        if (!accepted) {
            MineMate.LOGGER.error("[autotest] llm_http_retry postcondition {} requests={}",
                    outcome.toCompactString(), llmTransport.calls());
        }
        return accepted;
    }

    /**
     * The exact shape that broke llm_live, offline: live claude-sonnet-5 under adaptive thinking
     * answers an action order with correct tool calls and no text block, so a speechless action
     * reply must plan normally rather than fail the order.
     */
    private static boolean acceptLlmSpeechlessAction(PlanOutcome outcome) {
        boolean accepted = outcome.status() == PlanStatus.SUCCEEDED
                && llmTransport.calls() == 1
                && !journalHas(EventKind.PLANNER_FAILED);
        if (!accepted) {
            MineMate.LOGGER.error("[autotest] llm_speechless_action postcondition {} requests={}",
                    outcome.toCompactString(), llmTransport.calls());
        }
        return accepted;
    }

    // ---- schema and parse ---------------------------------------------------------------------

    /**
     * Checks the two translations that stand between the registry and the model, without a round
     * trip: the strict tool schema going out, and the null-stripping coming back. Both are pure
     * functions, and both fail in ways that would otherwise only show up as an opaque 400 or as
     * every defaulted parameter being rejected.
     */
    private static String llmSchemaViolation() {
        String registrySchema = ToolRegistry.defaults().schemaJson();
        if (registrySchema.contains(PlannerProtocol.STATUS_TOOL)) {
            return "the reserved status function leaked into ToolRegistry";
        }
        if (registrySchema.contains(PlannerProtocol.CONTRACT_TOOL)) {
            return "the reserved contract function leaked into ToolRegistry";
        }
        String hostedSchema;
        JsonArray tools;
        try {
            hostedSchema = PlannerProtocol.hostedSchema(registrySchema);
            tools = OpenAiToolSchemas.toOpenAiTools(hostedSchema);
        } catch (RuntimeException error) {
            return "the strict transform threw " + error;
        }
        if (tools.size() != ToolRegistry.defaults().tools().size() + 2) {
            return "the hosted transform produced " + tools.size() + " tools instead of "
                    + (ToolRegistry.defaults().tools().size() + 2);
        }
        for (JsonElement element : tools) {
            String violation = strictToolViolation(element.getAsJsonObject(), hostedSchema);
            if (violation != null) {
                return violation;
            }
        }
        JsonObject statusTool = null;
        JsonObject contractTool = null;
        for (JsonElement element : tools) {
            JsonObject tool = element.getAsJsonObject();
            if (PlannerProtocol.STATUS_TOOL.equals(tool.get("name").getAsString())) {
                statusTool = tool;
            }
            if (PlannerProtocol.CONTRACT_TOOL.equals(tool.get("name").getAsString())) {
                contractTool = tool;
            }
        }
        if (statusTool == null) {
            return "the hosted schema omitted the reserved status function";
        }
        JsonArray statuses = statusTool.getAsJsonObject("parameters")
                .getAsJsonObject("properties").getAsJsonObject("status").getAsJsonArray("enum");
        if (statuses.contains(JsonParser.parseString("\"ACTION\"")) || statuses.size() != 5
                || !statuses.contains(JsonParser.parseString("\"ANSWERED\""))) {
            return "the status function exposed the wrong dispositions";
        }
        if (contractTool == null) {
            return "the hosted schema omitted the reserved contract function";
        }
        // The strict transform rewrites only the top level, so the nested deliverable object must
        // arrive pre-strict; losing that would only ever show up as an opaque 400 from the provider.
        JsonObject deliverable = contractTool.getAsJsonObject("parameters")
                .getAsJsonObject("properties").getAsJsonObject("deliverables")
                .getAsJsonObject("items");
        JsonElement nestedAdditional = deliverable.get("additionalProperties");
        if (nestedAdditional == null || nestedAdditional.getAsBoolean()) {
            return "the contract deliverable did not forbid additional properties";
        }
        JsonArray nestedRequired = deliverable.getAsJsonArray("required");
        if (nestedRequired == null || nestedRequired.size() != 2
                || !nestedRequired.contains(JsonParser.parseString("\"item\""))
                || !nestedRequired.contains(JsonParser.parseString("\"count\""))) {
            return "the contract deliverable did not require item and count";
        }
        // fetch_items carries the registry's own array-of-objects parameter through the same
        // top-level-only transform, so its nested entry must also arrive pre-strict.
        JsonObject fetchItemsTool = toolByName(tools, "fetch_items");
        if (fetchItemsTool == null) {
            return "the hosted schema omitted fetch_items";
        }
        JsonObject itemsProperty = fetchItemsTool.getAsJsonObject("parameters")
                .getAsJsonObject("properties").getAsJsonObject("items");
        JsonElement itemsType = itemsProperty.get("type");
        if (!itemsType.isJsonPrimitive() || !"array".equals(itemsType.getAsString())) {
            return "fetch_items.items is required and must keep a plain array type";
        }
        if (!itemsProperty.has("minItems") || !itemsProperty.has("maxItems")) {
            return "fetch_items.items lost its length constraints";
        }
        JsonObject itemsEntry = itemsProperty.getAsJsonObject("items");
        JsonElement entryAdditional = itemsEntry.get("additionalProperties");
        if (entryAdditional == null || entryAdditional.getAsBoolean()) {
            return "the fetch_items entry did not forbid additional properties";
        }
        JsonArray entryRequired = itemsEntry.getAsJsonArray("required");
        if (entryRequired == null || entryRequired.size() != 2
                || !entryRequired.contains(JsonParser.parseString("\"item\""))
                || !entryRequired.contains(JsonParser.parseString("\"count\""))) {
            return "the fetch_items entry did not require item and count";
        }
        if (!itemsEntry.getAsJsonObject("properties").getAsJsonObject("count").has("maximum")) {
            return "the fetch_items entry count lost its range";
        }
        return nullStrippingViolation();
    }

    private static String llmProtocolViolation() {
        for (PlanDisposition expected : List.of(PlanDisposition.COMPLETED, PlanDisposition.ANSWERED,
                PlanDisposition.CLARIFICATION, PlanDisposition.UNSUPPORTED, PlanDisposition.BLOCKED)) {
            LlmReply reply;
            try {
                reply = OpenAiResponsesBackend.parseReply(
                        llmResponse(null, llmStatus(expected.name(), "Status message.")));
            } catch (RuntimeException error) {
                return expected + " terminal reply failed to parse: " + error.getMessage();
            }
            if (reply.disposition() != expected || !reply.calls().isEmpty()
                    || !"Status message.".equals(reply.dialogue())) {
                return expected + " terminal reply parsed incorrectly";
            }
        }

        String violation = expectMalformed("missing status", llmResponse("Done."));
        if (violation != null) {
            return violation;
        }
        violation = expectMalformed("mixed status and action", llmResponse("Working.",
                llmCall("goto", "{\"x\":1,\"z\":1}"),
                llmStatus("BLOCKED", "I cannot continue.")));
        if (violation != null) {
            return violation;
        }
        violation = expectMalformed("duplicate status", llmResponse(null,
                llmStatus("COMPLETED", "Done."), llmStatus("BLOCKED", "Blocked.")));
        if (violation != null) {
            return violation;
        }
        violation = expectMalformed("invalid ACTION status",
                llmResponse(null, llmStatus("ACTION", "Working.")));
        if (violation != null) {
            return violation;
        }
        violation = expectMalformed("terminal assistant message", llmResponse("Done.",
                llmStatus("COMPLETED", "Done.")));
        if (violation != null) {
            return violation;
        }
        violation = expectMalformed("nine-action reply",
                llmResponse("This is too much.", nineGotoCalls()));
        if (violation != null) {
            return violation;
        }

        // The contract call is protocol, not work: it must parse into the reply's contract and
        // never count as a world call.
        LlmReply contractReply;
        try {
            contractReply = OpenAiResponsesBackend.parseReply(llmResponse("On it.",
                    llmContract("[{\"item\":\"minecraft:Iron_Sword\",\"count\":1}]", true),
                    llmCall("fetch", "{\"resource\":\"iron_sword\",\"count\":1}")));
        } catch (RuntimeException error) {
            return "a contract-bearing action reply failed to parse: " + error.getMessage();
        }
        if (contractReply.calls().size() != 1
                || !"fetch".equals(contractReply.calls().get(0).tool())) {
            return "the contract call leaked into the world calls";
        }
        if (contractReply.contract() == null || !contractReply.contract().deliverToOwner()
                || contractReply.contract().deliverables().size() != 1
                || !"iron_sword".equals(contractReply.contract().deliverables().get(0).item())) {
            return "the contract did not parse and normalize";
        }
        violation = expectMalformed("duplicate contract", llmResponse("On it.",
                llmContract("[]", false), llmContract("[]", false),
                llmCall("goto", "{\"x\":1,\"z\":1}")));
        if (violation != null) {
            return violation;
        }
        violation = expectMalformed("contract on a terminal reply", llmResponse(null,
                llmContract("[]", false), llmStatus("COMPLETED", "Done.")));
        if (violation != null) {
            return violation;
        }
        // Parseable on purpose: single-call-habit models send exactly this, and dispatch must get
        // the chance to repair it instead of the parser failing the order.
        LlmReply contractOnly;
        try {
            contractOnly = OpenAiResponsesBackend.parseReply(llmResponse(
                    "On it, I will get you a bucket.",
                    llmContract("[{\"item\":\"bucket\",\"count\":1}]", true)));
        } catch (RuntimeException error) {
            return "a contract-only reply failed to parse: " + error.getMessage();
        }
        if (!contractOnly.calls().isEmpty() || contractOnly.contract() == null
                || contractOnly.disposition() != PlanDisposition.ACTION) {
            return "a contract-only reply did not parse into an empty action reply";
        }
        violation = expectMalformed("contract with a zero count", llmResponse("On it.",
                llmContract("[{\"item\":\"stick\",\"count\":0}]", true),
                llmCall("fetch", "{\"resource\":\"stick\",\"count\":1}")));
        if (violation != null) {
            return violation;
        }
        violation = expectMalformed("contract missing deliver_to_owner", llmResponse("On it.",
                llmCall(PlannerProtocol.CONTRACT_TOOL, "{\"deliverables\":[]}"),
                llmCall("goto", "{\"x\":1,\"z\":1}")));
        if (violation != null) {
            return violation;
        }
        // Legal on purpose, and provider-agnostic: the speechless-action habit is a model habit,
        // not a wire format, so both backends must parse tool calls with no assistant text.
        LlmReply speechless;
        try {
            speechless = OpenAiResponsesBackend.parseReply(
                    llmResponse(null, llmCall("goto", "{\"x\":1,\"z\":1}")));
        } catch (RuntimeException error) {
            return "a speechless action reply failed to parse: " + error.getMessage();
        }
        if (speechless.disposition() != PlanDisposition.ACTION || !speechless.dialogue().isBlank()
                || speechless.calls().size() != 1) {
            return "a speechless action reply did not parse into a one-call action reply";
        }
        return null;
    }

    private static String expectMalformed(String label, String body) {
        try {
            OpenAiResponsesBackend.parseReply(body);
            return label + " was accepted";
        } catch (LlmException expected) {
            return null;
        } catch (RuntimeException error) {
            return label + " threw the wrong error: " + error;
        }
    }

    /**
     * Checks the Anthropic request builder without a round trip: the neutral schema must land on
     * the wire verbatim -- no strict transform, no null unions, no OpenAI keys -- with the cache
     * breakpoint on the system block and the forced-tool choice spelled as an object. Driven
     * through {@code complete()} over a fixture transport because the builder itself is package
     * private to the llm package.
     */
    private static String llmClaudeSchemaViolation() {
        String registrySchema = ToolRegistry.defaults().schemaJson();
        String hostedSchema = PlannerProtocol.hostedSchema(registrySchema);
        FixtureTransport transport = FixtureTransport.of(FixtureTransport.ok(
                claudeResponse(null, claudeStatus("ANSWERED", "Schema probe."))));
        AnthropicMessagesBackend backend = new AnthropicMessagesBackend(transport,
                "http://fixture.invalid/v1", "fixture-model", "fixture-key",
                PlannerConfig.DEFAULT_EFFORT, PlannerConfig.DEFAULT_MAX_OUTPUT_TOKENS);
        backend.complete(new LlmPrompt(PlannerPrompt.SYSTEM, "ORDER: schema probe", hostedSchema, ""));
        JsonObject body;
        try {
            body = JsonParser.parseString(transport.lastRequestBody()).getAsJsonObject();
        } catch (RuntimeException error) {
            return "the request builder produced unparseable JSON: " + error;
        }
        JsonArray sentTools = body.getAsJsonArray("tools");
        JsonArray neutralTools = JsonParser.parseString(hostedSchema).getAsJsonArray();
        if (sentTools == null || sentTools.size() != ToolRegistry.defaults().tools().size() + 2) {
            return "the request carried " + (sentTools == null ? 0 : sentTools.size())
                    + " tools instead of " + (ToolRegistry.defaults().tools().size() + 2);
        }
        for (int index = 0; index < sentTools.size(); index++) {
            JsonObject sent = sentTools.get(index).getAsJsonObject();
            String name = sent.has("name") ? sent.get("name").getAsString() : "tool[" + index + "]";
            if (sent.has("parameters") || sent.has("strict") || sent.has("type")) {
                return name + " leaked an OpenAI tool-shape key onto the Anthropic wire";
            }
            if (!sent.equals(neutralTools.get(index).getAsJsonObject())) {
                return name + " was not the neutral registry schema verbatim";
            }
        }
        // The bounds Anthropic strict mode could not express are exactly why strict is off; they
        // must still ride along as ordinary schema for the model to read.
        JsonObject mine = toolByName(sentTools, "mine");
        if (mine == null || !mine.getAsJsonObject("input_schema").getAsJsonObject("properties")
                .getAsJsonObject("count").has("maximum")) {
            return "mine.count lost its range";
        }
        JsonObject deposit = toolByName(sentTools, "deposit");
        if (deposit == null || !deposit.getAsJsonObject("input_schema").getAsJsonObject("properties")
                .getAsJsonObject("container").has("minItems")) {
            return "deposit.container lost its length constraint";
        }
        JsonObject fetchItems = toolByName(sentTools, "fetch_items");
        if (fetchItems == null || !fetchItems.getAsJsonObject("input_schema")
                .getAsJsonObject("properties").getAsJsonObject("items").has("maxItems")) {
            return "fetch_items.items lost its length constraint";
        }
        JsonArray system = body.getAsJsonArray("system");
        if (system == null || system.size() != 1) {
            return "the system prompt was not a single block";
        }
        JsonObject systemBlock = system.get(0).getAsJsonObject();
        if (!PlannerPrompt.SYSTEM.equals(systemBlock.get("text").getAsString())) {
            return "the system block did not carry PlannerPrompt.SYSTEM byte-identical";
        }
        if (!systemBlock.has("cache_control") || !"ephemeral".equals(
                systemBlock.getAsJsonObject("cache_control").get("type").getAsString())) {
            return "the system block lost its cache_control breakpoint";
        }
        if (!body.has("tool_choice")
                || !"auto".equals(body.getAsJsonObject("tool_choice").get("type").getAsString())) {
            return "tool_choice was not the {type: auto} object";
        }
        if (!body.has("max_tokens")) {
            return "max_tokens was missing, which this endpoint rejects";
        }
        if (!body.has("output_config") || !PlannerConfig.DEFAULT_EFFORT.equals(
                body.getAsJsonObject("output_config").get("effort").getAsString())) {
            return "output_config.effort did not carry the configured reasoning effort";
        }
        if (body.has("temperature") || body.has("top_p") || body.has("parallel_tool_calls")
                || body.has("thinking")) {
            return "the request carried a parameter this backend must omit";
        }
        JsonArray messages = body.getAsJsonArray("messages");
        if (messages == null || messages.size() != 1 || !"ORDER: schema probe".equals(
                messages.get(0).getAsJsonObject().get("content").getAsString())) {
            return "the user message did not carry the prompt verbatim";
        }
        return null;
    }

    private static JsonObject toolByName(JsonArray tools, String name) {
        for (JsonElement element : tools) {
            JsonObject tool = element.getAsJsonObject();
            if (name.equals(tool.get("name").getAsString())) {
                return tool;
            }
        }
        return null;
    }

    private static String llmClaudeProtocolViolation() {
        for (PlanDisposition expected : List.of(PlanDisposition.COMPLETED, PlanDisposition.ANSWERED,
                PlanDisposition.CLARIFICATION, PlanDisposition.UNSUPPORTED, PlanDisposition.BLOCKED)) {
            LlmReply reply;
            try {
                reply = AnthropicMessagesBackend.parseReply(
                        claudeResponse(null, claudeStatus(expected.name(), "Status message.")));
            } catch (RuntimeException error) {
                return expected + " terminal reply failed to parse: " + error.getMessage();
            }
            if (reply.disposition() != expected || !reply.calls().isEmpty()
                    || !"Status message.".equals(reply.dialogue())) {
                return expected + " terminal reply parsed incorrectly";
            }
        }

        String violation = expectClaudeMalformed("missing status",
                claudeResponse("end_turn", "Done."));
        if (violation != null) {
            return violation;
        }
        violation = expectClaudeMalformed("mixed status and action", claudeResponse("Working.",
                claudeCall("goto", "{\"x\":1,\"z\":1}"),
                claudeStatus("BLOCKED", "I cannot continue.")));
        if (violation != null) {
            return violation;
        }
        violation = expectClaudeMalformed("duplicate status", claudeResponse(null,
                claudeStatus("COMPLETED", "Done."), claudeStatus("BLOCKED", "Blocked.")));
        if (violation != null) {
            return violation;
        }
        violation = expectClaudeMalformed("invalid ACTION status",
                claudeResponse(null, claudeStatus("ACTION", "Working.")));
        if (violation != null) {
            return violation;
        }
        violation = expectClaudeMalformed("terminal assistant message", claudeResponse("Done.",
                claudeStatus("COMPLETED", "Done.")));
        if (violation != null) {
            return violation;
        }
        JsonObject[] nine = new JsonObject[Plan.MAX_STEPS + 1];
        for (int index = 0; index < nine.length; index++) {
            nine[index] = claudeCall("goto", "{\"x\":" + (index + 1) + ",\"z\":0}");
        }
        violation = expectClaudeMalformed("nine-action reply",
                claudeResponse("This is too much.", nine));
        if (violation != null) {
            return violation;
        }

        // The happy path also proves the block-array handling: dialogue split across two text
        // blocks, behind a thinking block, with the contract kept out of the world calls.
        LlmReply contractReply;
        try {
            contractReply = AnthropicMessagesBackend.parseReply(claudeResponse("On",
                    claudeText("it."),
                    claudeContract("[{\"item\":\"minecraft:Iron_Sword\",\"count\":1}]", true),
                    claudeCall("fetch", "{\"resource\":\"iron_sword\",\"count\":1}")));
        } catch (RuntimeException error) {
            return "a contract-bearing action reply failed to parse: " + error.getMessage();
        }
        if (!"On it.".equals(contractReply.dialogue())) {
            return "the parser lost or mangled the split text blocks: " + contractReply.dialogue();
        }
        if (contractReply.calls().size() != 1
                || !"fetch".equals(contractReply.calls().get(0).tool())) {
            return "the contract call leaked into the world calls";
        }
        if (contractReply.contract() == null || !contractReply.contract().deliverToOwner()
                || contractReply.contract().deliverables().size() != 1
                || !"iron_sword".equals(contractReply.contract().deliverables().get(0).item())) {
            return "the contract did not parse and normalize";
        }
        violation = expectClaudeMalformed("duplicate contract", claudeResponse("On it.",
                claudeContract("[]", false), claudeContract("[]", false),
                claudeCall("goto", "{\"x\":1,\"z\":1}")));
        if (violation != null) {
            return violation;
        }
        violation = expectClaudeMalformed("contract on a terminal reply", claudeResponse(null,
                claudeContract("[]", false), claudeStatus("COMPLETED", "Done.")));
        if (violation != null) {
            return violation;
        }
        // Parseable on purpose: single-call-habit models send exactly this, and dispatch must get
        // the chance to continue it instead of the parser failing the order.
        LlmReply contractOnly;
        try {
            contractOnly = AnthropicMessagesBackend.parseReply(claudeResponse(
                    "On it, I will get you a bucket.",
                    claudeContract("[{\"item\":\"bucket\",\"count\":1}]", true)));
        } catch (RuntimeException error) {
            return "a contract-only reply failed to parse: " + error.getMessage();
        }
        if (!contractOnly.calls().isEmpty() || contractOnly.contract() == null
                || contractOnly.disposition() != PlanDisposition.ACTION) {
            return "a contract-only reply did not parse into an empty action reply";
        }
        violation = expectClaudeMalformed("contract with a zero count", claudeResponse("On it.",
                claudeContract("[{\"item\":\"stick\",\"count\":0}]", true),
                claudeCall("fetch", "{\"resource\":\"stick\",\"count\":1}")));
        if (violation != null) {
            return violation;
        }
        violation = expectClaudeMalformed("contract missing deliver_to_owner", claudeResponse("On it.",
                claudeCall(PlannerProtocol.CONTRACT_TOOL, "{\"deliverables\":[]}"),
                claudeCall("goto", "{\"x\":1,\"z\":1}")));
        if (violation != null) {
            return violation;
        }
        // Legal on purpose: live claude-sonnet-5 under adaptive thinking reliably sends tool
        // calls with no text block, so blank dialogue must parse rather than fail the order.
        LlmReply speechless;
        try {
            speechless = AnthropicMessagesBackend.parseReply(
                    claudeResponse(null, claudeCall("goto", "{\"x\":1,\"z\":1}")));
        } catch (RuntimeException error) {
            return "a speechless action reply failed to parse: " + error.getMessage();
        }
        if (speechless.disposition() != PlanDisposition.ACTION || !speechless.dialogue().isBlank()
                || speechless.calls().size() != 1) {
            return "a speechless action reply did not parse into a one-call action reply";
        }
        // Truncation announces itself only through stop_reason; the blocks may look complete.
        violation = expectClaudeMalformed("truncated reply", claudeResponse("max_tokens",
                "Partial plan.", claudeCall("goto", "{\"x\":1,\"z\":1}")));
        if (violation != null) {
            return violation;
        }
        violation = expectClaudeMalformed("string tool input",
                claudeResponse("Sure.", claudeCall("mine", "\"oops\"")));
        if (violation != null) {
            return violation;
        }
        violation = expectClaudeMalformed("reply without content",
                "{\"id\":\"msg_fixture\",\"type\":\"message\",\"stop_reason\":\"end_turn\"}");
        if (violation != null) {
            return violation;
        }
        return null;
    }

    private static String expectClaudeMalformed(String label, String body) {
        try {
            AnthropicMessagesBackend.parseReply(body);
            return label + " was accepted";
        } catch (LlmException expected) {
            return null;
        } catch (RuntimeException error) {
            return label + " threw the wrong error: " + error;
        }
    }

    private static String strictToolViolation(JsonObject tool, String registrySchema) {
        String name = tool.get("name").getAsString();
        if (!"function".equals(tool.get("type").getAsString())) {
            return name + " lost its function type";
        }
        if (tool.has("function")) {
            return name + " used the nested chat-completions shape";
        }
        if (!tool.get("strict").getAsBoolean()) {
            return name + " was not marked strict";
        }
        JsonObject parameters = tool.getAsJsonObject("parameters");
        JsonElement additional = parameters.get("additionalProperties");
        if (additional == null || additional.getAsBoolean()) {
            return name + " did not forbid additional properties";
        }
        JsonObject properties = parameters.getAsJsonObject("properties");
        Set<String> required = new HashSet<>();
        for (JsonElement entry : parameters.getAsJsonArray("required")) {
            required.add(entry.getAsString());
        }
        if (!required.equals(properties.keySet())) {
            return name + " did not list every property as required";
        }
        Set<String> originallyRequired = registryRequired(registrySchema, name);
        for (String property : properties.keySet()) {
            JsonElement type = properties.getAsJsonObject(property).get("type");
            boolean nullable = type.isJsonArray() && type.getAsJsonArray().contains(
                    JsonParser.parseString("\"null\""));
            if (originallyRequired.contains(property) && nullable) {
                return name + "." + property + " was made optional";
            }
            if (!originallyRequired.contains(property) && !nullable) {
                // Without the null union, strict mode makes an optional parameter mandatory and the
                // model can no longer decline to supply it.
                return name + "." + property + " lost its optionality";
            }
        }
        // The bounds are honoured under strict mode, so losing them would be a silent downgrade.
        if ("mine".equals(name) && !properties.getAsJsonObject("count").has("maximum")) {
            return "mine.count lost its range";
        }
        if ("deposit".equals(name) && !properties.getAsJsonObject("container").has("minItems")) {
            return "deposit.container lost its length constraint";
        }
        return null;
    }

    private static Set<String> registryRequired(String registrySchema, String toolName) {
        Set<String> required = new HashSet<>();
        for (JsonElement element : JsonParser.parseString(registrySchema).getAsJsonArray()) {
            JsonObject tool = element.getAsJsonObject();
            if (!toolName.equals(tool.get("name").getAsString())) {
                continue;
            }
            for (JsonElement entry : tool.getAsJsonObject("input_schema").getAsJsonArray("required")) {
                required.add(entry.getAsString());
            }
        }
        return required;
    }

    private static String nullStrippingViolation() {
        LlmReply reply = OpenAiResponsesBackend.parseReply(llmResponse("Reading you.",
                llmCall("mine", "{\"resource\":\"dirt\",\"count\":null}")));
        if (!"Reading you.".equals(reply.dialogue())) {
            return "the parser lost the dialogue behind the reasoning item";
        }
        if (reply.calls().size() != 1) {
            return "the parser produced " + reply.calls().size() + " calls";
        }
        ToolCall call = reply.calls().get(0);
        if (call.arguments().has("count")) {
            return "a null argument was not stripped";
        }
        // The point of stripping: an absent optional argument is what the registry defaults.
        if (!(ToolRegistry.defaults().validate(call) instanceof ToolValidation.Accepted)) {
            return "a stripped null did not validate";
        }
        return null;
    }

    private static void prepareContextJournal() {
        prepareInventory(new ItemStack(Items.DIRT, 3));
        prepareFakeOwner(CONTEXT_OWNER_UUID, "MineMateContextOwner");
        // Logged once per run so the text a planner actually receives is reviewable, not inferred.
        MineMate.LOGGER.info("[autotest] world context sample:\n{}",
                companion.getContext().snapshot().render());
    }

    /**
     * Asserts the snapshot and journal describe a populated world, and — the point of the scenario
     * — that replan triggers stay rare while the companion ticks at 20 Hz.
     */
    private static String contextJournalInvariant() {
        String violation = contextInvariant();
        if (violation != null) {
            return violation;
        }
        WorldSnapshot snapshot = companion.getContext().snapshot();
        if (!snapshot.owner().present()) {
            return "the owner was missing from the snapshot";
        }
        if (snapshot.self().maxHealth() <= 0.0F) {
            return "self state was not populated";
        }
        if (snapshot.dimension().isEmpty()) {
            return "the dimension was missing";
        }
        // Five brakes gate firing; over a scenario this short the ceiling is a handful.
        int fired = companion.getContext().triggers().firedCount();
        if (fired > 4) {
            return "replan triggers fired " + fired + " times in one short scenario";
        }
        return null;
    }

    private static boolean acceptContextJournal(TaskOutcome outcome) {
        // A floor as well as the invariant's ceiling. The ceiling alone is satisfied by a trigger
        // system that fires nothing at all, which is exactly how a broken global cooldown reads.
        // The goal's own outcome arms TASK_OUTCOME, and the settle tick before this runs dispatches
        // it, so anything less than one means the dispatch path is dead.
        int fired = companion.getContext().triggers().firedCount();
        boolean accepted = succeeded(outcome) && companion.getJournal().size() > 0 && fired >= 1;
        if (!accepted) {
            MineMate.LOGGER.error(
                    "[autotest] context_journal postcondition entries={} triggers={} render=\n{}",
                    companion.getJournal().size(), fired, companion.getContext().snapshot().render());
        }
        return accepted;
    }

    private static void clearContextJournal(MinecraftServer server) {
        clearFakeOwner();
        clearFixtures();
    }

    private static boolean inPlanScenario() {
        return scenarioIndex < scenarios.size() && scenarios.get(scenarioIndex).isPlan();
    }

    private static boolean inProbeScenario() {
        return scenarioIndex < scenarios.size() && scenarios.get(scenarioIndex).isProbe();
    }

    /**
     * Runs against every scenario, not just the context one: the snapshot is read by the planner on
     * any tick, so it must never throw, blow its budget, or emit non-ASCII in any world state.
     */
    private static String contextInvariant() {
        if (companion == null || companion.getContext() == null) {
            return null;
        }
        WorldSnapshot snapshot;
        String rendered;
        try {
            snapshot = companion.getContext().snapshot();
            rendered = snapshot.render();
        } catch (Throwable error) {
            return "capture or render threw " + error;
        }
        if (rendered.isBlank()) {
            return "render produced nothing";
        }
        if (rendered.length() > WorldSnapshot.RenderBudget.DEFAULT.maxChars()) {
            return "render was " + rendered.length() + " chars, over budget";
        }
        for (int index = 0; index < rendered.length(); index++) {
            char character = rendered.charAt(index);
            if (character > 127) {
                return "render emitted a non-ASCII character at " + index;
            }
        }
        if (!rendered.startsWith("SELF ") || !rendered.contains("\nWORLD ")) {
            return "render lost a mandatory section";
        }
        // Memoized per game tick: a second capture in the same tick must be the same instance.
        if (companion.getContext().snapshot() != snapshot) {
            return "snapshot was recaptured within a single tick";
        }
        if (companion.getJournal().size() > CompanionJournal.CAPACITY) {
            return "journal grew past its capacity";
        }
        long previous = Long.MIN_VALUE;
        for (JournalEntry entry : companion.getJournal().since(Long.MIN_VALUE)) {
            if (entry.gameTime() < previous) {
                return "journal entries are out of order";
            }
            previous = entry.gameTime();
        }
        return null;
    }

    /**
     * Schema-level checks on the tool registry. These cost no ticks and cover the rejection paths a
     * planner will hit constantly, which the goal scenarios cannot reach because they call
     * {@code Goals.*} directly.
     */
    private static String toolRegistryViolation() {
        ToolRegistry registry = ToolRegistry.defaults();
        if (registry.tools().size() != 10) {
            return "expected 10 tools, found " + registry.tools().size();
        }
        String rejected = expectRejected(registry, ToolCall.of("teleport"), Reasons.UNKNOWN_TOOL);
        if (rejected != null) {
            return rejected;
        }
        rejected = expectRejected(registry, ToolCall.of("mine"), Reasons.MISSING_ARGUMENT);
        if (rejected != null) {
            return rejected;
        }
        rejected = expectRejected(registry,
                ToolCall.of("mine").with("resource", "dirt").with("count", 9999),
                Reasons.ARGUMENT_OUT_OF_RANGE);
        if (rejected != null) {
            return rejected;
        }
        rejected = expectRejected(registry,
                ToolCall.of("mine").with("resource", "unobtainium"), Reasons.UNKNOWN_RESOURCE);
        if (rejected != null) {
            return rejected;
        }
        rejected = expectRejected(registry,
                ToolCall.of("mine").with("resource", "dirt").with("sideways", 1),
                Reasons.UNKNOWN_ARGUMENT);
        if (rejected != null) {
            return rejected;
        }
        rejected = expectRejected(registry,
                ToolCall.of("craft").with("item", "minecraft:unobtainium"), Reasons.UNKNOWN_ITEM);
        if (rejected != null) {
            return rejected;
        }
        // NaN passes both range comparisons, so without an explicit finite check it would validate
        // and reach pathfinding as a value nothing downstream can recognise.
        rejected = expectRejected(registry,
                ToolCall.of("goto").with("x", 0).with("z", 0).with("radius", "NaN"),
                Reasons.INVALID_ARGUMENT);
        if (rejected != null) {
            return rejected;
        }
        rejected = expectRejected(registry,
                ToolCall.of("goto").with("x", 0).with("z", 0).with("radius", "Infinity"),
                Reasons.INVALID_ARGUMENT);
        if (rejected != null) {
            return rejected;
        }

        // fetch_items: per-entry validation of the one array-of-objects parameter.
        rejected = expectRejected(registry,
                fetchItemsCall("[{\"item\":\"unobtainium\",\"count\":1}]"),
                Reasons.UNKNOWN_RESOURCE);
        if (rejected != null) {
            return rejected;
        }
        rejected = expectRejected(registry, fetchItemsCall("[]"), Reasons.ARGUMENT_OUT_OF_RANGE);
        if (rejected != null) {
            return rejected;
        }
        StringBuilder nineEntries = new StringBuilder("[");
        for (int index = 0; index < 9; index++) {
            if (index > 0) {
                nineEntries.append(',');
            }
            nineEntries.append("{\"item\":\"stick\",\"count\":").append(index + 1).append('}');
        }
        rejected = expectRejected(registry, fetchItemsCall(nineEntries.append(']').toString()),
                Reasons.ARGUMENT_OUT_OF_RANGE);
        if (rejected != null) {
            return rejected;
        }
        rejected = expectRejected(registry, fetchItemsCall("[{\"item\":\"stick\",\"count\":0}]"),
                Reasons.ARGUMENT_OUT_OF_RANGE);
        if (rejected != null) {
            return rejected;
        }
        rejected = expectRejected(registry,
                fetchItemsCall("[{\"item\":\"stick\",\"count\":1,\"extra\":true}]"),
                Reasons.UNKNOWN_ARGUMENT);
        if (rejected != null) {
            return rejected;
        }
        rejected = expectRejected(registry, fetchItemsCall("[{\"item\":\"stick\"}]"),
                Reasons.MISSING_ARGUMENT);
        if (rejected != null) {
            return rejected;
        }
        // Duplicate entries merge deterministically instead of failing an otherwise-good plan.
        ToolValidation mergedItems = registry.validate(fetchItemsCall(
                "[{\"item\":\"stick\",\"count\":2},{\"item\":\"stick\",\"count\":3}]"));
        if (!(mergedItems instanceof ToolValidation.Accepted acceptedItems)
                || acceptedItems.arguments().getItemQuantities("items").size() != 1
                || acceptedItems.arguments().getItemQuantities("items").get(0).count() != 5) {
            return "duplicate fetch_items entries did not merge";
        }

        // A count arriving as a JSON string is the most common real planner slip; coerce it.
        ToolValidation coerced = registry.validate(
                ToolCall.of("mine").with("resource", "dirt").with("count", "4"));
        if (!(coerced instanceof ToolValidation.Accepted accepted)) {
            return "a string count was not coerced: " + ((ToolValidation.Rejected) coerced).reason();
        }
        if (accepted.arguments().getInt("count") != 4) {
            return "a string count coerced to " + accepted.arguments().getInt("count");
        }

        // An omitted optional argument must arrive as its declared default.
        ToolValidation defaulted = registry.validate(ToolCall.of("mine").with("resource", "dirt"));
        if (!(defaulted instanceof ToolValidation.Accepted withDefault)
                || withDefault.arguments().getInt("count") != 1) {
            return "the count default was not applied";
        }

        // The raw-JSON path a planner uses must survive malformed input rather than throwing.
        if (ToolCall.parseList("{\"not\":\"an array\"}") != null
                || ToolCall.parseList("[{\"missing\":\"tool\"}]") != null
                || ToolCall.parseList("not json at all") != null) {
            return "malformed planner JSON was accepted";
        }
        List<ToolCall> parsed = ToolCall.parseList(
                "[{\"tool\":\"mine\",\"arguments\":{\"resource\":\"dirt\",\"count\":2}}]");
        if (parsed == null || parsed.size() != 1
                || !(registry.validate(parsed.get(0)) instanceof ToolValidation.Accepted)) {
            return "a well formed planner call did not parse and validate";
        }
        if (registry.schemaJson().isBlank() || !registry.schemaJson().contains("input_schema")) {
            return "the tool schema was not emitted";
        }
        return null;
    }

    /** Builds a fetch_items call from raw items JSON; ToolCall.with has no JSON-element overload. */
    private static ToolCall fetchItemsCall(String itemsJson) {
        JsonObject arguments = new JsonObject();
        arguments.add("items", JsonParser.parseString(itemsJson));
        return new ToolCall("fetch_items", arguments);
    }

    private static String expectRejected(ToolRegistry registry, ToolCall call, String expectedReason) {
        ToolValidation validation = registry.validate(call);
        if (!(validation instanceof ToolValidation.Rejected rejected)) {
            return call.toCompactString() + " was accepted; expected " + expectedReason;
        }
        return expectedReason.equals(rejected.reason()) ? null
                : call.toCompactString() + " gave " + rejected.reason() + ", expected " + expectedReason;
    }

    private static boolean acceptMobDefenseReflex(TaskOutcome outcome) {
        boolean accepted = succeeded(outcome) && sawMobDefenseReflex && sawMobDefenseClear;
        if (!accepted) {
            MineMate.LOGGER.error(
                    "[autotest] mob defense postcondition seen={} cleared={} hostile={} difficulty={} suppressed={} hostiles={} target={} reflex={}",
                    sawMobDefenseReflex, sawMobDefenseClear,
                    hostile == null ? "null" : "alive=" + hostile.isAlive() + ",removed=" + hostile.isRemoved(),
                    companion.level().getDifficulty(),
                    companion.getController().getShouldDefendFromHostiles(),
                    companion.getController().getEntityTracker().getHostiles().size(),
                    hostile == null ? "null" : String.valueOf(hostile.getTarget()),
                    companion.getReflexSnapshot().kind());
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

    private static void prepareFetchPlainResource() {
        prepareInventory();
        prepareFakeOwner(PLAIN_FETCH_OWNER_UUID, "MineMatePlainFetchOwner");
        observedFetchDrops.clear();
        observedFetchThrows.clear();
        for (int offset = 0; offset < 5; offset++) {
            placeFixtureBlock(spawn.offset(3 + offset, 0, 6), Blocks.OAK_LOG);
        }
    }

    private static void clearFetchPlainResource(MinecraftServer server) {
        clearFakeOwner();
        observedFetchDrops.clear();
        observedFetchThrows.clear();
        clearFixtures();
    }

    private static void prepareFetchCompositePipeline() {
        // The iron-sword fixture set supports the whole bucket pipeline too: logs for a crafting
        // table, cobblestone for a furnace, three iron ore for the ingots, coal for fuel.
        prepareIronSwordWorld();
        prepareFakeOwner(PIPELINE_FETCH_OWNER_UUID, "MineMatePipelineOwner");
        observedFetchDrops.clear();
        observedFetchThrows.clear();
        observedPipelinePhases.clear();
    }

    private static void clearFetchCompositePipeline(MinecraftServer server) {
        clearFakeOwner();
        observedFetchDrops.clear();
        observedFetchThrows.clear();
        clearFixtures();
    }

    private static boolean acceptFetchCompositePipeline(TaskOutcome outcome) {
        int delivered = fakeOwnerInventoryCount(Items.BUCKET);
        boolean accepted = succeeded(outcome) && delivered >= 1 && !observedPipelinePhases.isEmpty();
        if (!accepted) {
            MineMate.LOGGER.error(
                    "[autotest] fetch_composite_pipeline postcondition delivered={} phases={} "
                            + "ironIngots={} buckets={} engineState={} engineLog={}",
                    delivered, observedPipelinePhases, inventoryCount(Items.IRON_INGOT),
                    inventoryCount(Items.BUCKET), outcome.engineState(), outcome.engineLog());
        }
        return accepted;
    }

    private static String fetchCompositePipelineInvariant() {
        companion.getSupervisor().currentProgress().ifPresent(progress -> {
            for (String word : PIPELINE_PHASE_WORDS) {
                if (progress.text().contains(word)) {
                    observedPipelinePhases.add(word);
                }
            }
        });
        return compositeActivityInvariant();
    }

    private static void prepareFetchUnavailable() {
        prepareInventory();
        prepareFakeOwner(UNAVAILABLE_FETCH_OWNER_UUID, "MineMateUnavailableOwner");
        observedFetchDrops.clear();
        observedFetchThrows.clear();
    }

    private static void clearFetchUnavailable(MinecraftServer server) {
        clearFakeOwner();
        observedFetchDrops.clear();
        observedFetchThrows.clear();
        clearFixtures();
    }

    private static void prepareFetchItemsDelivery() {
        prepareInventory(new ItemStack(Items.OAK_PLANKS, 4), new ItemStack(Items.STICK, 4));
        prepareFakeOwner(FETCH_ITEMS_OWNER_UUID, "MineMateFetchItemsOwner");
        observedFetchDrops.clear();
        observedFetchThrows.clear();
    }

    private static void clearFetchItemsDelivery(MinecraftServer server) {
        clearFakeOwner();
        observedFetchDrops.clear();
        observedFetchThrows.clear();
        clearFixtures();
    }

    private static boolean acceptFetchItemsDelivery(TaskOutcome outcome) {
        int planks = fakeOwnerInventoryCount(Items.OAK_PLANKS);
        int sticks = fakeOwnerInventoryCount(Items.STICK);
        // have/want count whole entries, not item units: 2/2, never 8/8.
        boolean accepted = succeeded(outcome) && planks >= 4 && sticks >= 4
                && outcome.have() == 2 && outcome.want() == 2;
        if (!accepted) {
            MineMate.LOGGER.error(
                    "[autotest] fetch_items postcondition planks={} sticks={} have={} want={} "
                            + "outcome={} detail={}",
                    planks, sticks, outcome.have(), outcome.want(),
                    outcome.status() + "/" + outcome.reason(), outcome.detail());
        }
        return accepted;
    }

    private static void prepareFetchItemsBestEffort() {
        prepareInventory(new ItemStack(Items.DIRT, 2));
        prepareFakeOwner(BEST_EFFORT_OWNER_UUID, "MineMateBestEffortOwner");
        observedFetchDrops.clear();
        observedFetchThrows.clear();
    }

    private static void clearFetchItemsBestEffort(MinecraftServer server) {
        clearFakeOwner();
        observedFetchDrops.clear();
        observedFetchThrows.clear();
        clearFixtures();
    }

    private static boolean acceptFetchItemsBestEffort(TaskOutcome outcome) {
        int dirt = fakeOwnerInventoryCount(Items.DIRT);
        boolean accepted = outcome.status() == OutcomeStatus.BLOCKED
                && Reasons.RESOURCE_UNAVAILABLE.equals(outcome.reason())
                && dirt >= 2
                && outcome.have() == 1 && outcome.want() == 2
                && outcome.detail() != null
                && outcome.detail().contains("dirt")
                && outcome.detail().contains("bucket");
        if (!accepted) {
            MineMate.LOGGER.error(
                    "[autotest] fetch_items_best_effort postcondition dirt={} have={} want={} "
                            + "outcome={} detail={}",
                    dirt, outcome.have(), outcome.want(),
                    outcome.status() + "/" + outcome.reason(), outcome.detail());
        }
        return accepted;
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
        boolean fetchActive = companion.getSupervisor().currentGoal()
                .map(goal -> "fetch".equals(goal.kind()) || "fetch_items".equals(goal.kind()))
                .orElse(false);
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
            // A loaded FakePlayer does not run the ordinary player collision/pickup loop. Simulate
            // that loop for delivery drops close enough to touch the owner; otherwise non-boat
            // fetches wait forever after correctly acquiring and throwing their items.
            if (fetchActive) {
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
        // Every scenario setup funnels through here, so this is the one place that stops one
        // scenario's damage, pickups and triggers from being read as another's context.
        if (companion.getContext() != null) {
            companion.getContext().reset();
        }
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
        provokeHostileEachTick = true;
        provokeHostile();
        companion.getController().getEntityTracker().setDirty();
        companion.getController().getEntityTracker().ensureUpdated();
        // KillAura starts with an infinite force-field range, so this restores its default.
        companion.getController().getMobDefenseChain().resetForceField();
        companion.getController().getMobDefenseChain().setTargetEntity(hostile);
    }

    private static void clearMobDefenseReflex(MinecraftServer server) {
        System.clearProperty("minemate.autotest.unleashMobDefense");
        provokeHostileEachTick = false;
        companion.getController().getMobDefenseChain().resetTargetEntity();
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
    /**
     * A scenario is either goal-shaped (submit one goal, assert its {@link TaskOutcome}) or
     * plan-shaped (run a plan, assert its {@link PlanOutcome}); the unused pair of fields is null.
     *
     * <p>The two goal-shaped convenience constructors keep every pre-existing scenario literal
     * compiling unchanged, so adding plan support cannot perturb the goal suite.
     *
     * <p>A plan-shaped scenario whose {@code plan} factory returns null is one the setup dispatched
     * asynchronously; the harness then watches for any plan outcome it has not already seen.
     */
    private record Scenario(String name,
            Function<CompanionEntity, Goal> goal, Predicate<TaskOutcome> accepts,
            Function<CompanionEntity, Plan> plan, Predicate<PlanOutcome> acceptsPlan,
            Supplier<Boolean> ready, Supplier<String> verdict,
            int hardCapTicks,
            Consumer<MinecraftServer> setup, Consumer<MinecraftServer> teardown,
            Supplier<String> invariant) {

        private Scenario(String name, Function<CompanionEntity, Goal> goal,
                Predicate<TaskOutcome> accepts, int hardCapTicks) {
            this(name, goal, accepts, null, null, null, null, hardCapTicks, server -> {
            }, server -> {
            }, () -> null);
        }

        private Scenario(String name, Function<CompanionEntity, Goal> goal,
                Predicate<TaskOutcome> accepts, int hardCapTicks,
                Consumer<MinecraftServer> setup, Consumer<MinecraftServer> teardown,
                Supplier<String> invariant) {
            this(name, goal, accepts, null, null, null, null, hardCapTicks, setup, teardown, invariant);
        }

        private static Scenario plan(String name, Function<CompanionEntity, Plan> plan,
                Predicate<PlanOutcome> acceptsPlan, int hardCapTicks,
                Consumer<MinecraftServer> setup, Consumer<MinecraftServer> teardown,
                Supplier<String> invariant) {
            return new Scenario(name, null, null, plan, acceptsPlan, null, null, hardCapTicks,
                    setup, teardown, invariant);
        }

        /**
         * A scenario that asserts on something other than an outcome.
         *
         * <p>Half of what stage 5 has to get right produces no outcome at all: a planner that fails,
         * times out, answers with speech alone, or is stopped mid-flight must leave the companion
         * idle and quiet, and "nothing happened" is not something the goal or plan watchers can
         * observe. {@code ready} says when to look and {@code verdict} returns null for pass or the
         * failure text, the same idiom the invariants already use.
         */
        private static Scenario probe(String name, Supplier<Boolean> ready, Supplier<String> verdict,
                int hardCapTicks, Consumer<MinecraftServer> setup, Consumer<MinecraftServer> teardown,
                Supplier<String> invariant) {
            return new Scenario(name, null, null, null, null, ready, verdict, hardCapTicks,
                    setup, teardown, invariant);
        }

        private boolean isPlan() {
            return acceptsPlan != null;
        }

        private boolean isProbe() {
            return verdict != null;
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
