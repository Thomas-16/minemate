package io.github.thomas16.minemate.dev;

import com.player2.playerengine.tasks.base.Task;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.entity.MineMateEntities;
import io.github.thomas16.minemate.task.Goal;
import io.github.thomas16.minemate.task.GoalProgress;
import io.github.thomas16.minemate.task.Goals;
import io.github.thomas16.minemate.task.OutcomeStatus;
import io.github.thomas16.minemate.task.Reasons;
import io.github.thomas16.minemate.task.TaskListener;
import io.github.thomas16.minemate.task.TaskListeners;
import io.github.thomas16.minemate.task.TaskOutcome;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Sequential headless verification for the task supervisor, enabled with
 * {@code gradlew runServer -Dminemate.autotest=true}.
 */
public final class DevAutoTest {
    private static final int GLOBAL_CAP_TICKS = 6000;

    private static CompanionEntity companion;
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

    private DevAutoTest() {
    }

    public static void init() {
        if (!Boolean.getBoolean("minemate.autotest")) {
            return;
        }
        MineMate.LOGGER.info("[autotest] enabled — will run task supervisor scenarios");

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
        BlockPos spawn = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level.getSharedSpawnPos());
        companion = new CompanionEntity(MineMateEntities.COMPANION, level);
        companion.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0.0F, 0.0F);
        level.addFreshEntity(companion);
        level.setBlockAndUpdate(spawn.offset(3, 0, 0), Blocks.DIRT.defaultBlockState());

        try {
            Goals.fetch("unobtainium", 1);
            fail(server, "unknown_resource did not reject an invalid catalogue name");
            return;
        } catch (IllegalArgumentException expected) {
            MineMate.LOGGER.info("[autotest] unknown_resource PASS");
        }

        scenarios = List.of(
                new Scenario("terminal_resample",
                        ignored -> terminalResampleGoal(),
                        outcome -> outcome.status() == OutcomeStatus.SUCCEEDED
                                && Reasons.GOAL_MET.equals(outcome.reason()),
                        100),
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
                new Scenario("goto_timeout",
                        ignored -> withTimeout(Goals.goTo(spawn.getX() + 100_000, spawn.getZ(), 2.5), 200),
                        outcome -> outcome.status() == OutcomeStatus.TIMED_OUT
                                && Reasons.TIMEOUT.equals(outcome.reason()),
                        400),
                new Scenario("cancel",
                        ignored -> Goals.goTo(spawn.getX() + 100_000, spawn.getZ(), 2.5),
                        outcome -> outcome.status() == OutcomeStatus.CANCELLED
                                && Reasons.CANCELLED_BY_OWNER.equals(outcome.reason()),
                        400));
        scenarioIndex = 0;
        startScenario(server);
    }

    private static void tick(MinecraftServer server) {
        if (companion == null || done) {
            return;
        }
        globalTicks++;
        if (listenerFailure != null) {
            fail(server, listenerFailure);
            return;
        }
        if (globalTicks > GLOBAL_CAP_TICKS) {
            fail(server, "global tick cap exceeded");
            return;
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
        if (currentOutcome == null || !scenario.accepts().test(currentOutcome)) {
            fail(server, scenario.name() + " returned "
                    + (currentOutcome == null ? "no outcome" : currentOutcome.toCompactString()));
            return;
        }
        MineMate.LOGGER.info("[autotest] {} PASS — {}", scenario.name(), currentOutcome.toCompactString());
        scenarioIndex++;
        if (scenarioIndex >= scenarios.size()) {
            done = true;
            MineMate.LOGGER.info("[autotest] AUTOTEST PASS — all {} scenarios passed", scenarios.size());
            despawnCompanion();
            server.halt(false);
            return;
        }
        startScenario(server);
    }

    private static Goal withTimeout(Goal goal, int timeoutTicks) {
        return new Goal(goal.id(), goal.kind(), goal.description(), goal.taskFactory(), goal.probe(),
                timeoutTicks, goal.stallTicks());
    }

    private static Goal terminalResampleGoal() {
        return new Goal(Goal.nextId(), "test", "verify terminal progress",
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
        MineMate.LOGGER.error("[autotest] AUTOTEST FAIL — {}", reason);
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

    private record Scenario(String name, Function<CompanionEntity, Goal> goal,
            Predicate<TaskOutcome> accepts, int hardCapTicks) {
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
}
