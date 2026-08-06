package io.github.thomas16.minemate.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.debug.CompanionDebugNetworking;
import io.github.thomas16.minemate.entity.MineMateEntities;
import io.github.thomas16.minemate.task.Goal;
import io.github.thomas16.minemate.task.GoalProgress;
import io.github.thomas16.minemate.task.Reasons;
import io.github.thomas16.minemate.task.TaskOutcome;
import io.github.thomas16.minemate.task.TaskActivitySnapshot;
import io.github.thomas16.minemate.context.WorldSnapshot;
import io.github.thomas16.minemate.integration.ReflexSnapshot;
import io.github.thomas16.minemate.planner.HostedPlanner;
import io.github.thomas16.minemate.planner.PlanRequest;
import io.github.thomas16.minemate.planner.Planner;
import io.github.thomas16.minemate.planner.PlannerDispatch;
import io.github.thomas16.minemate.planner.Planners;
import io.github.thomas16.minemate.planner.ScriptedPlanner;
import io.github.thomas16.minemate.planner.StandingOrder;
import io.github.thomas16.minemate.tool.ToolBindException;
import io.github.thomas16.minemate.tool.ToolCall;
import io.github.thomas16.minemate.tool.ToolContext;
import io.github.thomas16.minemate.tool.ToolMessages;
import io.github.thomas16.minemate.tool.ToolRegistry;
import io.github.thomas16.minemate.tool.ToolValidation;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/** Temporary developer commands for spawning and directing the companion. */
public final class CompanionCommands {

    private CompanionCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext registryAccess) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("companion");
        root.then(Commands.literal("spawn")
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> spawn(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name")))));
        root.then(Commands.literal("dismiss").executes(ctx -> dismiss(ctx.getSource())));
        root.then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())));
        root.then(Commands.literal("status").executes(ctx -> status(ctx.getSource())));
        root.then(Commands.literal("debugUI").executes(ctx -> debugUi(ctx.getSource())));
        root.then(Commands.literal("context").executes(ctx -> context(ctx.getSource())));
        root.then(Commands.literal("tools").executes(ctx -> tools(ctx.getSource())));
        root.then(Commands.literal("planner").executes(ctx -> planner(ctx.getSource())));
        root.then(Commands.literal("plan")
                .then(Commands.literal("cancel").executes(ctx -> planCancel(ctx.getSource())))
                .then(Commands.argument("request", StringArgumentType.greedyString())
                        .executes(ctx -> plan(ctx.getSource(),
                                StringArgumentType.getString(ctx, "request")))));
        CompanionTaskCommands.attach(root, registryAccess);
        dispatcher.register(root);
    }

    private static int stop(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        CompanionEntity companion = findCompanion(player);
        if (companion == null) {
            source.sendFailure(Component.literal("No companion nearby"));
            return 0;
        }
        // Unconditional and separate from the branch below, because a planner request can be in
        // flight while the supervisor is busy with a manual order. Without this, a plan requested
        // moments ago would arrive and start seconds after the owner said stop.
        companion.getPlanExecutor().discardPendingPlannerRequests();
        // Stop means stop: no later event may revive this order through the replan path.
        companion.closeStandingOrder();
        // The plan must be cancelled first. Cancelling only the supervisor lets the executor start
        // the very next step on the following tick, so "stop" would appear to do nothing.
        if (companion.getPlanExecutor().isActive()) {
            companion.getPlanExecutor().cancel(Reasons.CANCELLED_BY_OWNER);
        } else if (companion.getSupervisor().isBusy()) {
            companion.getSupervisor().cancel(Reasons.CANCELLED_BY_OWNER);
        } else {
            companion.getController().cancelUserTask();
        }
        source.sendSuccess(() -> Component.literal("Companion stopped"), false);
        return 1;
    }

    private static int context(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        CompanionEntity companion = findCompanion(player);
        if (companion == null) {
            source.sendFailure(Component.literal("No companion nearby"));
            return 0;
        }
        WorldSnapshot snapshot = companion.getContext().snapshot();
        for (String line : snapshot.render().split("\n")) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        source.sendSuccess(() -> Component.literal(
                "(~" + snapshot.estimatedTokens() + " tokens)"), false);
        return 1;
    }

    private static int tools(CommandSourceStack source) {
        String schema = ToolRegistry.defaults().schemaJson();
        // Chat truncates long lines, so emit the schema in readable chunks.
        for (int start = 0; start < schema.length(); start += 250) {
            String chunk = schema.substring(start, Math.min(schema.length(), start + 250));
            source.sendSuccess(() -> Component.literal(chunk), false);
        }
        return 1;
    }

    /** Reports which brain is installed and how much budget the open order has left. Never the key. */
    private static int planner(CommandSourceStack source) {
        Planner active = Planners.active();
        String line = active instanceof HostedPlanner hosted
                ? hosted.config().describe()
                : active.name() + " (no hosted planner configured)";
        source.sendSuccess(() -> Component.literal(line), false);

        ServerPlayer player = requirePlayer(source);
        CompanionEntity companion = player == null ? null : findCompanion(player);
        if (companion == null) {
            return 1;
        }
        StandingOrder order = companion.getStandingOrder();
        int budget = active instanceof HostedPlanner hosted ? hosted.config().maxReplansPerOrder() : 0;
        String orderLine = order == null
                ? "order: none"
                : "order: \"%s\" replans %d/%d".formatted(
                        order.requestText(), order.replansUsed(), budget);
        source.sendSuccess(() -> Component.literal(orderLine), false);
        return 1;
    }

    private static int plan(CommandSourceStack source, String requestText) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        CompanionEntity companion = findCompanion(player);
        if (companion == null) {
            source.sendFailure(Component.literal("No companion nearby"));
            return 0;
        }
        // Match the interactive feel of the chat verbs: a new request supersedes the old one.
        if (companion.getPlanExecutor().isActive()) {
            companion.getPlanExecutor().cancel(Reasons.SUPERSEDED);
        }
        // A fresh order, with a fresh replan budget. This is the only place one is opened.
        companion.openStandingOrder(requestText);
        if (Planners.active() instanceof ScriptedPlanner scripted) {
            scripted.bind(companion);
        }
        WorldSnapshot snapshot = companion.getContext().snapshot();
        PlanRequest request = new PlanRequest(requestText,
                companion.getCompanionName().orElse(""), snapshot, snapshot.render(), null,
                ToolRegistry.defaults().schemaJson());
        PlannerDispatch.request(companion, Planners.active(), request);
        source.sendSuccess(() -> Component.literal("Planning: " + requestText), false);
        return 1;
    }

    private static int planCancel(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        CompanionEntity companion = findCompanion(player);
        if (companion == null) {
            source.sendFailure(Component.literal("No companion nearby"));
            return 0;
        }
        companion.closeStandingOrder();
        if (companion.getPlanExecutor().isActive()) {
            companion.getPlanExecutor().cancel(Reasons.CANCELLED_BY_OWNER);
            source.sendSuccess(() -> Component.literal("Plan cancelled"), false);
            return 1;
        }
        // Nothing is running yet, but the planner may still be composing an answer.
        if (companion.getPlanExecutor().isPlannerRequestPending()) {
            companion.getPlanExecutor().discardPendingPlannerRequests();
            source.sendSuccess(() -> Component.literal("Pending plan request cancelled"), false);
            return 1;
        }
        source.sendFailure(Component.literal("No plan is running"));
        return 0;
    }

    private static int status(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        CompanionEntity companion = findCompanion(player);
        if (companion == null) {
            source.sendFailure(Component.literal("No companion nearby"));
            return 0;
        }
        Optional<Goal> current = companion.getSupervisor().currentGoal();
        if (current.isPresent()) {
            Optional<GoalProgress> progress = companion.getSupervisor().currentProgress();
            String suffix = progress.map(value -> " - " + value.text()).orElse(" - waiting to start");
            Optional<TaskActivitySnapshot> activity = companion.getSupervisor().currentActivity();
            String scope = " - " + current.get().executionProfile().scope().name().toLowerCase();
            String phase = activity.map(TaskActivitySnapshot::phase)
                    .filter(value -> !value.isBlank() && progress.map(p -> !value.equals(p.text())).orElse(true))
                    .map(value -> " - " + value).orElse("");
            String paused = activity.filter(TaskActivitySnapshot::preempted)
                    .map(value -> " - paused: " + value.preemptingChain()).orElse("");
            String budget = activity.map(value -> " - ticks " + value.elapsedTicks() + "/"
                    + (value.timeoutTicks() == 0 ? "unbounded" : value.timeoutTicks())).orElse("");
            String taskTree = activity.map(TaskActivitySnapshot::taskTree)
                    .filter(value -> !value.isBlank())
                    .map(value -> " - task: " + bounded(value, 180)).orElse("");
            String reflex = reflexSuffix(companion.getReflexSnapshot());
            String plan = companion.getPlanExecutor().currentPlan()
                    .map(value -> " - plan " + value.id() + " step "
                            + (companion.getPlanExecutor().currentStepIndex() + 1)
                            + "/" + value.steps().size())
                    .orElse("");
            source.sendSuccess(
                    () -> Component.literal("Current: " + current.get().description()
                            + scope + plan + suffix + budget + phase + taskTree + paused + reflex), false);
            return 1;
        }
        Optional<TaskOutcome> outcome = companion.getSupervisor().lastOutcome();
        String text = outcome.map(value -> "Idle - last: " + value.detail()).orElse("Idle")
                + reflexSuffix(companion.getReflexSnapshot());
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int debugUi(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        CompanionEntity companion = findCompanion(player);
        if (companion == null) {
            source.sendFailure(Component.literal("No companion nearby"));
            return 0;
        }
        if (!CompanionDebugNetworking.open(player, companion)) {
            source.sendFailure(Component.literal("MineMate's client mod is required for the debug UI"));
            return 0;
        }
        return 1;
    }

    private static String reflexSuffix(ReflexSnapshot reflex) {
        if (reflex == null || !reflex.active()) {
            return reflex != null && reflex.defenseSuppressed() ? " - mob defense leashed" : "";
        }
        String detail = reflex.detail().isBlank() ? "" : ": " + reflex.detail();
        return " - reflex " + reflex.kind().name().toLowerCase() + detail;
    }

    private static String bounded(String value, int maximum) {
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= maximum
                ? oneLine : oneLine.substring(0, Math.max(0, maximum - 3)) + "...";
    }

    private static int spawn(CommandSourceStack source, String requestedName) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        String companionName;
        try {
            companionName = CompanionEntity.normalizeCompanionName(requestedName);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        CompanionEntity existing = findCompanion(player);
        if (existing != null) {
            source.sendFailure(Component.literal("You already have a companion"));
            return 0;
        }
        CompanionEntity companion = new CompanionEntity(MineMateEntities.COMPANION, level);
        companion.setCompanionName(companionName);
        companion.setOwnerUuid(player.getUUID());
        BlockPos pos = player.blockPosition().offset(1, 0, 1);
        companion.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), 0.0F);
        level.addFreshEntity(companion);
        source.sendSuccess(() -> Component.literal("Companion spawned: " + companionName), false);
        return 1;
    }

    private static int dismiss(CommandSourceStack source) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        CompanionEntity companion = findCompanion(player);
        if (companion == null) {
            source.sendFailure(Component.literal("No companion nearby"));
            return 0;
        }
        companion.discard();
        source.sendSuccess(() -> Component.literal("Companion dismissed"), false);
        return 1;
    }

    static ServerPlayer requirePlayer(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Player-only command"));
        }
        return player;
    }

    /**
     * Validates a tool call and binds it to a goal, reporting any rejection to the source.
     * Returns null when the call was refused, in which case nothing was submitted.
     */
    static Goal buildTool(CommandSourceStack source, CompanionEntity companion, ToolCall call) {
        ToolValidation validation = ToolRegistry.defaults().validate(call);
        if (validation instanceof ToolValidation.Rejected rejected) {
            source.sendFailure(Component.literal(
                    ToolMessages.owner(rejected.reason(), rejected.detail())));
            return null;
        }
        ToolValidation.Accepted accepted = (ToolValidation.Accepted) validation;
        try {
            return accepted.tool().builder().build(accepted.arguments(), ToolContext.of(companion));
        } catch (ToolBindException error) {
            source.sendFailure(Component.literal(
                    ToolMessages.owner(error.reason(), error.getMessage())));
            return null;
        } catch (IllegalArgumentException | IllegalStateException error) {
            // A goal factory that rejects its own arguments for a reason the schema cannot express.
            String message = error.getMessage() == null ? "Invalid companion task" : error.getMessage();
            source.sendFailure(Component.literal(message));
            return null;
        }
    }

    /** Finds the player's own companion within 128 blocks. */
    static CompanionEntity findCompanion(ServerPlayer player) {
        List<CompanionEntity> companions = player.serverLevel().getEntitiesOfClass(
                CompanionEntity.class,
                player.getBoundingBox().inflate(128.0),
                candidate -> candidate.isAlive()
                        && candidate.getOwnerUuid().map(player.getUUID()::equals).orElse(false));
        return companions.isEmpty() ? null : companions.get(0);
    }
}
