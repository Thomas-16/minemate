package io.github.thomas16.minemate.debug;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import com.player2.playerengine.tasks.base.TaskChain;

import io.github.thomas16.minemate.entity.CompanionEntity;
import io.github.thomas16.minemate.integration.ReflexSnapshot;
import io.github.thomas16.minemate.task.Goal;
import io.github.thomas16.minemate.task.GoalProgress;
import io.github.thomas16.minemate.task.TaskActivitySnapshot;
import io.github.thomas16.minemate.task.TaskOutcome;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable, registry-aware state sent to the read-only companion debug screen. */
public record CompanionDebugSnapshot(
        long serverGameTime,
        int entityId,
        UUID companionId,
        UUID ownerId,
        String ownerName,
        String dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        double velocityX,
        double velocityY,
        double velocityZ,
        String pose,
        boolean alive,
        boolean onGround,
        boolean sprinting,
        boolean crouching,
        boolean swimming,
        boolean fallFlying,
        boolean inWater,
        boolean inLava,
        boolean glowing,
        boolean invulnerable,
        boolean noGravity,
        float health,
        float maxHealth,
        float absorption,
        int armor,
        int food,
        float saturation,
        float exhaustion,
        int air,
        int maxAir,
        int fireTicks,
        int frozenTicks,
        int freezeThreshold,
        float fallDistance,
        List<EffectState> effects,
        List<ItemStack> mainInventory,
        List<ItemStack> armorInventory,
        ItemStack offhand,
        int selectedSlot,
        GoalState goal,
        ProgressState progress,
        ActivityState activity,
        EngineState engine,
        ReflexState reflex,
        OutcomeState lastOutcome) {

    private static final int MAX_STRING = 4096;
    private static final int MAIN_SLOTS = 36;
    private static final int ARMOR_SLOTS = 4;
    private static final int MAX_EFFECTS = 64;

    public CompanionDebugSnapshot {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(ownerId, "ownerId");
        ownerName = safe(ownerName);
        dimension = safe(dimension);
        pose = safe(pose);
        effects = List.copyOf(effects);
        mainInventory = copyStacks(mainInventory);
        armorInventory = copyStacks(armorInventory);
        offhand = offhand == null ? ItemStack.EMPTY : offhand.copy();
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(reflex, "reflex");
    }

    public static CompanionDebugSnapshot capture(CompanionEntity companion, ServerPlayer viewer) {
        Objects.requireNonNull(companion, "companion");
        Objects.requireNonNull(viewer, "viewer");
        if (companion.level().isClientSide || companion.getController() == null
                || companion.getSupervisor() == null) {
            throw new IllegalArgumentException("Companion debug snapshots are server-side only");
        }

        UUID ownerId = companion.getOwnerUuid().orElse(new UUID(0L, 0L));
        String ownerName = ownerId.equals(viewer.getUUID())
                ? viewer.getGameProfile().getName()
                : ownerId.toString();
        LivingEntityInventory inventory = companion.getLivingInventory();
        List<ItemStack> main = inventory.main.stream().map(ItemStack::copy).toList();
        List<ItemStack> armorItems = inventory.armor.stream().map(ItemStack::copy).toList();

        Optional<Goal> currentGoal = companion.getSupervisor().currentGoal();
        Optional<GoalProgress> currentProgress = companion.getSupervisor().currentProgress();
        Optional<TaskActivitySnapshot> currentActivity = companion.getSupervisor().currentActivity();
        Optional<TaskOutcome> outcome = companion.getSupervisor().lastOutcome();
        PlayerEngineController controller = companion.getController();
        TaskChain currentChain = controller.getTaskRunner().getCurrentTaskChain();
        ReflexSnapshot reflex = companion.getReflexSnapshot();
        Vec3 velocity = companion.getDeltaMovement();

        List<EffectState> effects = companion.getActiveEffects().stream()
                .map(CompanionDebugSnapshot::effectState)
                .toList();

        return new CompanionDebugSnapshot(
                companion.level().getGameTime(),
                companion.getId(),
                companion.getUUID(),
                ownerId,
                ownerName,
                companion.level().dimension().location().toString(),
                companion.getX(), companion.getY(), companion.getZ(),
                companion.getYRot(), companion.getXRot(),
                velocity.x, velocity.y, velocity.z,
                companion.getPose().name().toLowerCase(Locale.ROOT),
                companion.isAlive(), companion.onGround(), companion.isSprinting(),
                companion.isCrouching(), companion.isSwimming(), companion.isFallFlying(),
                companion.isInWater(), companion.isInLava(), companion.isCurrentlyGlowing(),
                companion.isInvulnerable(), companion.isNoGravity(),
                companion.getHealth(), companion.getMaxHealth(), companion.getAbsorptionAmount(),
                companion.getArmorValue(), companion.getHungerManager().getFoodLevel(),
                companion.getHungerManager().getSaturationLevel(),
                companion.getHungerManager().getExhaustion(),
                companion.getAirSupply(), companion.getMaxAirSupply(),
                companion.getRemainingFireTicks(), companion.getTicksFrozen(),
                companion.getTicksRequiredToFreeze(), companion.fallDistance,
                effects, main, armorItems, inventory.offHand.get(0).copy(), inventory.selectedSlot,
                currentGoal.map(GoalState::from).orElse(null),
                currentProgress.map(ProgressState::from).orElse(null),
                currentActivity.map(ActivityState::from).orElse(null),
                new EngineState(safe(controller.getChainStatusDisplayName()),
                        currentChain == null ? "" : safe(currentChain.getName()),
                        controller.getBaritone().getPathingBehavior().isPathing(),
                        controller.isPaused(), controller.isStopping),
                ReflexState.from(reflex),
                outcome.map(OutcomeState::from).orElse(null));
    }

    private static EffectState effectState(MobEffectInstance effect) {
        String id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString();
        return new EffectState(id, effect.getAmplifier(), effect.getDuration(), effect.isAmbient(),
                effect.isVisible(), effect.showIcon());
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(serverGameTime);
        buffer.writeVarInt(entityId);
        buffer.writeUUID(companionId);
        buffer.writeUUID(ownerId);
        writeString(buffer, ownerName);
        writeString(buffer, dimension);
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
        buffer.writeFloat(yaw);
        buffer.writeFloat(pitch);
        buffer.writeDouble(velocityX);
        buffer.writeDouble(velocityY);
        buffer.writeDouble(velocityZ);
        writeString(buffer, pose);
        buffer.writeBoolean(alive);
        buffer.writeBoolean(onGround);
        buffer.writeBoolean(sprinting);
        buffer.writeBoolean(crouching);
        buffer.writeBoolean(swimming);
        buffer.writeBoolean(fallFlying);
        buffer.writeBoolean(inWater);
        buffer.writeBoolean(inLava);
        buffer.writeBoolean(glowing);
        buffer.writeBoolean(invulnerable);
        buffer.writeBoolean(noGravity);
        buffer.writeFloat(health);
        buffer.writeFloat(maxHealth);
        buffer.writeFloat(absorption);
        buffer.writeVarInt(armor);
        buffer.writeVarInt(food);
        buffer.writeFloat(saturation);
        buffer.writeFloat(exhaustion);
        buffer.writeVarInt(air);
        buffer.writeVarInt(maxAir);
        buffer.writeVarInt(fireTicks);
        buffer.writeVarInt(frozenTicks);
        buffer.writeVarInt(freezeThreshold);
        buffer.writeFloat(fallDistance);

        buffer.writeVarInt(effects.size());
        for (EffectState effect : effects) {
            effect.write(buffer);
        }
        writeStacks(buffer, mainInventory);
        writeStacks(buffer, armorInventory);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, offhand);
        buffer.writeVarInt(selectedSlot);
        writeOptional(buffer, goal, GoalState::write);
        writeOptional(buffer, progress, ProgressState::write);
        writeOptional(buffer, activity, ActivityState::write);
        engine.write(buffer);
        reflex.write(buffer);
        writeOptional(buffer, lastOutcome, OutcomeState::write);
    }

    public static CompanionDebugSnapshot read(RegistryFriendlyByteBuf buffer) {
        long serverGameTime = buffer.readLong();
        int entityId = buffer.readVarInt();
        UUID companionId = buffer.readUUID();
        UUID ownerId = buffer.readUUID();
        String ownerName = readString(buffer);
        String dimension = readString(buffer);
        double x = buffer.readDouble();
        double y = buffer.readDouble();
        double z = buffer.readDouble();
        float yaw = buffer.readFloat();
        float pitch = buffer.readFloat();
        double velocityX = buffer.readDouble();
        double velocityY = buffer.readDouble();
        double velocityZ = buffer.readDouble();
        String pose = readString(buffer);
        boolean alive = buffer.readBoolean();
        boolean onGround = buffer.readBoolean();
        boolean sprinting = buffer.readBoolean();
        boolean crouching = buffer.readBoolean();
        boolean swimming = buffer.readBoolean();
        boolean fallFlying = buffer.readBoolean();
        boolean inWater = buffer.readBoolean();
        boolean inLava = buffer.readBoolean();
        boolean glowing = buffer.readBoolean();
        boolean invulnerable = buffer.readBoolean();
        boolean noGravity = buffer.readBoolean();
        float health = buffer.readFloat();
        float maxHealth = buffer.readFloat();
        float absorption = buffer.readFloat();
        int armorValue = buffer.readVarInt();
        int food = buffer.readVarInt();
        float saturation = buffer.readFloat();
        float exhaustion = buffer.readFloat();
        int air = buffer.readVarInt();
        int maxAir = buffer.readVarInt();
        int fireTicks = buffer.readVarInt();
        int frozenTicks = buffer.readVarInt();
        int freezeThreshold = buffer.readVarInt();
        float fallDistance = buffer.readFloat();

        int effectCount = boundedCount(buffer.readVarInt(), MAX_EFFECTS, "effects");
        List<EffectState> effects = new ArrayList<>(effectCount);
        for (int index = 0; index < effectCount; index++) {
            effects.add(EffectState.read(buffer));
        }
        List<ItemStack> main = readStacks(buffer, MAIN_SLOTS, "main inventory");
        List<ItemStack> armor = readStacks(buffer, ARMOR_SLOTS, "armor inventory");
        ItemStack offhand = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
        int selectedSlot = buffer.readVarInt();
        GoalState goal = readOptional(buffer, GoalState::read);
        ProgressState progress = readOptional(buffer, ProgressState::read);
        ActivityState activity = readOptional(buffer, ActivityState::read);
        EngineState engine = EngineState.read(buffer);
        ReflexState reflex = ReflexState.read(buffer);
        OutcomeState lastOutcome = readOptional(buffer, OutcomeState::read);

        return new CompanionDebugSnapshot(serverGameTime, entityId, companionId, ownerId,
                ownerName, dimension, x, y, z, yaw, pitch, velocityX, velocityY, velocityZ,
                pose, alive, onGround, sprinting, crouching, swimming, fallFlying, inWater,
                inLava, glowing, invulnerable, noGravity, health, maxHealth, absorption,
                armorValue, food, saturation, exhaustion, air, maxAir, fireTicks, frozenTicks,
                freezeThreshold, fallDistance, effects, main, armor, offhand, selectedSlot,
                goal, progress, activity, engine, reflex, lastOutcome);
    }

    private static void writeStacks(RegistryFriendlyByteBuf buffer, List<ItemStack> stacks) {
        buffer.writeVarInt(stacks.size());
        for (ItemStack stack : stacks) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack);
        }
    }

    private static List<ItemStack> readStacks(RegistryFriendlyByteBuf buffer, int expected, String label) {
        int size = boundedCount(buffer.readVarInt(), expected, label);
        if (size != expected) {
            throw new IllegalArgumentException("Invalid " + label + " size: " + size);
        }
        List<ItemStack> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
        }
        return result;
    }

    private static int boundedCount(int count, int maximum, String label) {
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException("Invalid " + label + " count: " + count);
        }
        return count;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        Objects.requireNonNull(stacks, "stacks");
        return stacks.stream().map(stack -> stack == null ? ItemStack.EMPTY : stack.copy()).toList();
    }

    private static void writeString(RegistryFriendlyByteBuf buffer, String value) {
        buffer.writeUtf(safe(value), MAX_STRING);
    }

    private static String readString(RegistryFriendlyByteBuf buffer) {
        return buffer.readUtf(MAX_STRING);
    }

    private static <T> void writeOptional(RegistryFriendlyByteBuf buffer, T value, Writer<T> writer) {
        buffer.writeBoolean(value != null);
        if (value != null) {
            writer.write(value, buffer);
        }
    }

    private static <T> T readOptional(RegistryFriendlyByteBuf buffer, Reader<T> reader) {
        return buffer.readBoolean() ? reader.read(buffer) : null;
    }

    @FunctionalInterface
    private interface Writer<T> {
        void write(T value, RegistryFriendlyByteBuf buffer);
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(RegistryFriendlyByteBuf buffer);
    }

    public record EffectState(String id, int amplifier, int duration, boolean ambient,
            boolean visible, boolean showIcon) {
        public EffectState {
            id = safe(id);
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            writeString(buffer, id);
            buffer.writeVarInt(amplifier);
            buffer.writeVarInt(duration);
            buffer.writeBoolean(ambient);
            buffer.writeBoolean(visible);
            buffer.writeBoolean(showIcon);
        }

        private static EffectState read(RegistryFriendlyByteBuf buffer) {
            return new EffectState(readString(buffer), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean());
        }
    }

    public record GoalState(String id, String kind, String description, String scope,
            boolean mayAcquirePrerequisites, String sideEffects, int timeoutTicks, int stallTicks) {
        public GoalState {
            id = safe(id);
            kind = safe(kind);
            description = safe(description);
            scope = safe(scope);
            sideEffects = safe(sideEffects);
        }

        private static GoalState from(Goal goal) {
            return new GoalState(goal.id(), goal.kind(), goal.description(),
                    goal.executionProfile().scope().name(),
                    goal.executionProfile().mayAcquirePrerequisites(),
                    goal.executionProfile().sideEffects(), goal.timeoutTicks(), goal.stallTicks());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            writeString(buffer, id);
            writeString(buffer, kind);
            writeString(buffer, description);
            writeString(buffer, scope);
            buffer.writeBoolean(mayAcquirePrerequisites);
            writeString(buffer, sideEffects);
            buffer.writeVarInt(timeoutTicks);
            buffer.writeVarInt(stallTicks);
        }

        private static GoalState read(RegistryFriendlyByteBuf buffer) {
            return new GoalState(readString(buffer), readString(buffer), readString(buffer),
                    readString(buffer), buffer.readBoolean(), readString(buffer),
                    buffer.readVarInt(), buffer.readVarInt());
        }
    }

    public record ProgressState(int have, int want, double advance, String text) {
        public ProgressState {
            text = safe(text);
        }

        private static ProgressState from(GoalProgress progress) {
            return new ProgressState(progress.have(), progress.want(), progress.advance(), progress.text());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(have);
            buffer.writeInt(want);
            buffer.writeDouble(advance);
            writeString(buffer, text);
        }

        private static ProgressState read(RegistryFriendlyByteBuf buffer) {
            return new ProgressState(buffer.readVarInt(), buffer.readInt(), buffer.readDouble(), readString(buffer));
        }
    }

    public record ActivityState(int elapsedTicks, int timeoutTicks, String phase,
            String taskTree, String preemptingChain) {
        public ActivityState {
            phase = safe(phase);
            taskTree = safe(taskTree);
            preemptingChain = safe(preemptingChain);
        }

        private static ActivityState from(TaskActivitySnapshot activity) {
            return new ActivityState(activity.elapsedTicks(), activity.timeoutTicks(), activity.phase(),
                    activity.taskTree(), activity.preemptingChain());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(elapsedTicks);
            buffer.writeVarInt(timeoutTicks);
            writeString(buffer, phase);
            writeString(buffer, taskTree);
            writeString(buffer, preemptingChain);
        }

        private static ActivityState read(RegistryFriendlyByteBuf buffer) {
            return new ActivityState(buffer.readVarInt(), buffer.readVarInt(), readString(buffer),
                    readString(buffer), readString(buffer));
        }
    }

    public record EngineState(String status, String currentChain, boolean pathing,
            boolean paused, boolean stopping) {
        public EngineState {
            status = safe(status);
            currentChain = safe(currentChain);
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            writeString(buffer, status);
            writeString(buffer, currentChain);
            buffer.writeBoolean(pathing);
            buffer.writeBoolean(paused);
            buffer.writeBoolean(stopping);
        }

        private static EngineState read(RegistryFriendlyByteBuf buffer) {
            return new EngineState(readString(buffer), readString(buffer), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readBoolean());
        }
    }

    public record ReflexState(String kind, String chain, long startedAtTick,
            boolean defenseSuppressed, double ownerDistance, String detail) {
        public ReflexState {
            kind = safe(kind);
            chain = safe(chain);
            detail = safe(detail);
        }

        private static ReflexState from(ReflexSnapshot reflex) {
            if (reflex == null) {
                return new ReflexState("NONE", "", 0L, false, -1.0, "");
            }
            return new ReflexState(reflex.kind().name(), reflex.chain(), reflex.startedAtTick(),
                    reflex.defenseSuppressed(), reflex.ownerDistance(), reflex.detail());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            writeString(buffer, kind);
            writeString(buffer, chain);
            buffer.writeLong(startedAtTick);
            buffer.writeBoolean(defenseSuppressed);
            buffer.writeDouble(ownerDistance);
            writeString(buffer, detail);
        }

        private static ReflexState read(RegistryFriendlyByteBuf buffer) {
            return new ReflexState(readString(buffer), readString(buffer), buffer.readLong(),
                    buffer.readBoolean(), buffer.readDouble(), readString(buffer));
        }
    }

    public record OutcomeState(String goalId, String kind, String status, String reason,
            String detail, int moved, int have, int want, long elapsedMs,
            String engineState, String engineLog) {
        public OutcomeState {
            goalId = safe(goalId);
            kind = safe(kind);
            status = safe(status);
            reason = safe(reason);
            detail = safe(detail);
            engineState = safe(engineState);
            engineLog = safe(engineLog);
        }

        private static OutcomeState from(TaskOutcome outcome) {
            return new OutcomeState(outcome.goalId(), outcome.kind(), outcome.status().name(),
                    outcome.reason(), outcome.detail(), outcome.moved(), outcome.have(), outcome.want(),
                    outcome.elapsedMs(), outcome.engineState(), outcome.engineLog());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            writeString(buffer, goalId);
            writeString(buffer, kind);
            writeString(buffer, status);
            writeString(buffer, reason);
            writeString(buffer, detail);
            buffer.writeInt(moved);
            buffer.writeInt(have);
            buffer.writeInt(want);
            buffer.writeLong(elapsedMs);
            writeString(buffer, engineState);
            writeString(buffer, engineLog);
        }

        private static OutcomeState read(RegistryFriendlyByteBuf buffer) {
            return new OutcomeState(readString(buffer), readString(buffer), readString(buffer),
                    readString(buffer), readString(buffer), buffer.readInt(), buffer.readInt(),
                    buffer.readInt(), buffer.readLong(), readString(buffer), readString(buffer));
        }
    }
}
