package io.github.thomas16.minemate.entity;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.automaton.api.IBaritone;
import com.player2.playerengine.automaton.api.entity.IAutomatone;
import com.player2.playerengine.automaton.api.entity.IHungerManagerProvider;
import com.player2.playerengine.automaton.api.entity.IInteractionManagerProvider;
import com.player2.playerengine.automaton.api.entity.IInventoryProvider;
import com.player2.playerengine.automaton.api.entity.LivingEntityHungerManager;
import com.player2.playerengine.automaton.api.entity.LivingEntityInteractionManager;
import com.player2.playerengine.automaton.api.entity.LivingEntityInventory;
import com.player2.playerengine.player2api.Character;

import io.github.thomas16.minemate.MineMate;
import io.github.thomas16.minemate.context.CompanionContext;
import io.github.thomas16.minemate.context.CompanionJournal;
import io.github.thomas16.minemate.context.ConversationLog;
import io.github.thomas16.minemate.planner.ConversationCoordinator;
import io.github.thomas16.minemate.planner.PlanExecutor;
import io.github.thomas16.minemate.planner.StandingOrder;
import io.github.thomas16.minemate.integration.CompanionDefensePolicy;
import io.github.thomas16.minemate.integration.CompanionReflexTelemetry;
import io.github.thomas16.minemate.integration.ReflexSnapshot;
import io.github.thomas16.minemate.task.Reasons;
import io.github.thomas16.minemate.task.TaskSupervisor;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The AI companion body: a LivingEntity that PlayerEngine can control.
 * No vanilla mob AI — all movement comes from the PlayerEngine/Automatone
 * pathfinder attached to this entity.
 */
public class CompanionEntity extends LivingEntity
        implements IAutomatone, IInventoryProvider, IInteractionManagerProvider, IHungerManagerProvider {

    public static final int MAX_NAME_LENGTH = 32;

    private static final String OWNER_NBT_KEY = "minemate_owner";
    private static final String CONVERSATION_NBT_KEY = "minemate_conversation";

    /**
     * Placeholder identity for PlayerEngine's controller. We never set an
     * owner on the controller, so its Player2 auth/heartbeat/LLM paths stay
     * dormant — the companion's real brain will be our own planner.
     */
    private static final Character DUMMY_CHARACTER =
            new Character("minemate", "MineMate", "MineMate", "", "", "", new String[0]);

    private LivingEntityInteractionManager interactionManager;
    private LivingEntityInventory inventory;
    private LivingEntityHungerManager hungerManager;
    private PlayerEngineController controller;
    private TaskSupervisor supervisor;
    private CompanionReflexTelemetry reflexTelemetry;
    private CompanionContext context;
    private PlanExecutor planExecutor;
    private ConversationCoordinator conversationCoordinator;
    private StandingOrder standingOrder;
    private ConversationLog conversationLog;

    private UUID ownerUuid;
    private Vec3 lastVelocity = Vec3.ZERO;

    public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
        super(type, level);
        this.setSpeed(0.4F);
        this.interactionManager = new LivingEntityInteractionManager(this);
        this.inventory = new LivingEntityInventory(this);
        this.hungerManager = new LivingEntityHungerManager();
        if (!level.isClientSide) {
            this.controller = new PlayerEngineController(
                    IBaritone.KEY.get(this), DUMMY_CHARACTER, MineMate.MOD_ID);
            this.controller.setChatClefEnabled(false);
            CompanionDefensePolicy.install(this.controller);
            this.supervisor = new TaskSupervisor(this);
            this.reflexTelemetry = new CompanionReflexTelemetry(this, this.controller);
            this.context = new CompanionContext(this);
            this.planExecutor = new PlanExecutor(this);
            this.conversationCoordinator = new ConversationCoordinator(this);
            this.conversationLog = new ConversationLog(
                    MineMate.plannerConfig().chatTranscriptTurns(),
                    MineMate.plannerConfig().chatTranscriptChars());
        }
    }

    /** Server-side only; null on the client. */
    public PlayerEngineController getController() {
        return controller;
    }

    /** Server-side only; null on the client. */
    public TaskSupervisor getSupervisor() {
        return supervisor;
    }

    /** Latest server-side automatic-chain telemetry; null only on the client. */
    public ReflexSnapshot getReflexSnapshot() {
        return reflexTelemetry == null ? null : reflexTelemetry.snapshot();
    }

    /**
     * How long the current reflex has been running, in ticks; 0 when idle.
     *
     * <p>{@link ReflexSnapshot#startedAtTick()} is measured against the telemetry's own counter
     * rather than game time, so this is the only correct way to turn it into a duration.
     */
    public int reflexActiveTicks() {
        if (reflexTelemetry == null || !reflexTelemetry.snapshot().active()) {
            return 0;
        }
        return (int) Math.max(0L, reflexTelemetry.observedTicks() - reflexTelemetry.snapshot().startedAtTick());
    }

    /** Server-side only; null on the client. Owns the journal and the replan triggers. */
    public CompanionContext getContext() {
        return context;
    }

    /** Convenience for the common case; null on the client. */
    public CompanionJournal getJournal() {
        return context == null ? null : context.journal();
    }

    /** Server-side only; null on the client. */
    public PlanExecutor getPlanExecutor() {
        return planExecutor;
    }

    /** Server-side only; null on the client. Owns model-request concurrency and owner turns. */
    public ConversationCoordinator getConversationCoordinator() {
        return conversationCoordinator;
    }

    /** Server-side only; null on the client. Bounded conversational memory. */
    public ConversationLog getConversationLog() {
        return conversationLog;
    }

    /** The owner order being worked on, or null when the companion has no standing instruction. */
    public StandingOrder getStandingOrder() {
        return standingOrder;
    }

    /** Opens a fresh order with a full replan budget, replacing any order still open. */
    public void openStandingOrder(String requestText) {
        this.standingOrder = new StandingOrder(requestText, this.level().getGameTime());
    }

    public void closeStandingOrder() {
        this.standingOrder = null;
    }

    public Optional<UUID> getOwnerUuid() {
        return Optional.ofNullable(ownerUuid);
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    /**
     * Server-side owner lookup. A loaded Fabric {@code FakePlayer} is not necessarily in the
     * connected-player list, so the loaded-entity fallback is required for headless tests and
     * for any owner whose player entity outlives its connection.
     */
    public Optional<ServerPlayer> resolveOwner() {
        if (ownerUuid == null || this.getServer() == null || !(this.level() instanceof ServerLevel serverLevel)) {
            return Optional.empty();
        }
        ServerPlayer connected = this.getServer().getPlayerList().getPlayer(ownerUuid);
        if (connected != null) {
            return Optional.of(connected);
        }
        return serverLevel.getEntity(ownerUuid) instanceof ServerPlayer loaded
                ? Optional.of(loaded) : Optional.empty();
    }

    /** The owner, only when they are in this companion's level. Empty otherwise. */
    public Optional<ServerPlayer> resolveOwnerInLevel() {
        return resolveOwner().filter(owner -> owner.level() == this.level());
    }

    /** Plain-text identity intended for future planner and LLM context. */
    public Optional<String> getCompanionName() {
        return Optional.ofNullable(this.getCustomName()).map(Component::getString);
    }

    public void setCompanionName(String name) {
        String normalized = normalizeCompanionName(name);
        this.setCustomName(Component.literal(normalized));
        this.setCustomNameVisible(true);
    }

    public static String normalizeCompanionName(String name) {
        String normalized = Objects.requireNonNull(name, "name").strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Companion name must be between 1 and " + MAX_NAME_LENGTH + " characters");
        }
        return normalized;
    }

    @Override
    public LivingEntityInventory getLivingInventory() {
        return inventory;
    }

    @Override
    public LivingEntityInteractionManager getInteractionManager() {
        return interactionManager;
    }

    @Override
    public LivingEntityHungerManager getHungerManager() {
        return hungerManager;
    }

    @Override
    public void tick() {
        this.lastVelocity = this.getDeltaMovement();
        this.interactionManager.update();
        this.inventory.updateItems();
        ++this.attackStrengthTicker;
        if (!this.level().isClientSide && this.controller != null) {
            // Before serverTick: the leash decides which chain TaskRunner may pick this tick.
            CompanionDefensePolicy.tick(this, this.controller);
            this.controller.serverTick();
            this.reflexTelemetry.tick();
            this.supervisor.tick();
            // After the supervisor: it has published this tick's outcome, which is what the
            // executor polls to decide whether to advance. Context goes last, on settled state.
            this.planExecutor.tick();
            this.context.tick();
            // After context: this slice's replan triggers have been heard (and refused or
            // deferred) before a queued owner turn or a deferred replan is allowed to dispatch.
            this.conversationCoordinator.tick();
        }
        super.tick();
        this.updateSwingTime();
        this.hungerManager.update(this);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.level().isClientSide && this.controller != null) {
            // Before everything: no in-flight response, queued turn, or deferred replan may act
            // on a companion that is on its way out.
            this.conversationCoordinator.invalidateAll();
            // The shutdown below arms a replan trigger, and without this the autonomy
            // listener would answer it by planning for a companion that is on its way out.
            this.standingOrder = null;
            // Before the supervisor, so the plan reports its own terminal outcome rather than
            // having it misattributed to the goal cancellation the supervisor is about to emit.
            this.planExecutor.shutdown(Reasons.COMPANION_REMOVED);
            this.supervisor.shutdown(Reasons.COMPANION_REMOVED);
            this.controller.stop();
        }
        super.remove(reason);
    }

    /**
     * Journals damage here rather than by watching health, because this is the only point where the
     * attacker is still known.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        float before = this.getHealth();
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide && this.context != null) {
            this.context.onDamaged(source, before - this.getHealth());
        }
        return hurt;
    }

    @Override
    public void aiStep() {
        if (this.isInWater() && this.isShiftKeyDown() && this.isAffectedByFluids()) {
            this.goDownInWater();
        }
        super.aiStep();
        this.yHeadRot = this.getYRot();
        this.pickupItems();
    }

    private void pickupItems() {
        if (this.level().isClientSide || !this.isAlive() || this.dead
                || !this.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return;
        }
        Vec3i range = new Vec3i(3, 3, 3);
        for (ItemEntity itemEntity : this.level().getEntitiesOfClass(ItemEntity.class,
                this.getBoundingBox().inflate(range.getX(), range.getY(), range.getZ()))) {
            if (!itemEntity.isRemoved() && !itemEntity.getItem().isEmpty() && !itemEntity.hasPickUpDelay()) {
                ItemStack stack = itemEntity.getItem();
                int countBefore = stack.getCount();
                // insertStack mutates the stack; a fully consumed one reports AIR afterwards,
                // so the item has to be read before the call, not after.
                Item picked = stack.getItem();
                if (this.inventory.insertStack(stack)) {
                    int collected = countBefore - stack.getCount();
                    if (collected <= 0) {
                        continue;
                    }
                    this.take(itemEntity, collected);
                    if (this.context != null) {
                        this.context.onItemPickedUp(picked, collected);
                    }
                    if (stack.isEmpty()) {
                        itemEntity.discard();
                    } else {
                        itemEntity.setItem(stack);
                    }
                }
            }
        }
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        if (this.ownerUuid != null && this.ownerUuid.equals(target.getUUID())) {
            return false;
        }
        float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE);
        DamageSource damageSource = this.damageSources().mobAttack(this);
        if (this.level() instanceof ServerLevel serverLevel) {
            damage = EnchantmentHelper.modifyDamage(serverLevel, this.getWeaponItem(), target, damageSource, damage);
        }
        boolean hurt = target.hurt(damageSource, damage);
        if (hurt) {
            float knockback = this.getKnockback(target, damageSource);
            if (knockback > 0.0F && target instanceof LivingEntity livingTarget) {
                livingTarget.knockback(knockback * 0.5F,
                        Mth.sin(this.getYRot() * ((float) Math.PI / 180F)),
                        -Mth.cos(this.getYRot() * ((float) Math.PI / 180F)));
                this.setDeltaMovement(this.getDeltaMovement().multiply(0.6, 1.0, 0.6));
            }
            if (this.level() instanceof ServerLevel serverLevel) {
                EnchantmentHelper.doPostAttackEffects(serverLevel, target, damageSource);
            }
            this.setLastHurtMob(target);
        }
        return hurt;
    }

    @Override
    public void knockback(double strength, double x, double z) {
        if (this.hurtMarked) {
            super.knockback(strength, x, z);
        }
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return inventory.armor;
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        if (slot == EquipmentSlot.MAINHAND) {
            return inventory.getMainHandStack();
        } else if (slot == EquipmentSlot.OFFHAND) {
            return inventory.offHand.get(0);
        } else if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
            return inventory.armor.get(slot.getIndex());
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        if (slot == EquipmentSlot.MAINHAND) {
            inventory.setItem(inventory.selectedSlot, stack);
        } else if (slot == EquipmentSlot.OFFHAND) {
            inventory.offHand.set(0, stack);
        } else if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
            inventory.armor.set(slot.getIndex(), stack);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("head_yaw")) {
            this.yHeadRot = tag.getFloat("head_yaw");
        }
        ListTag inventoryTag = tag.getList("Inventory", CompoundTag.TAG_COMPOUND);
        this.inventory.readNbt(level().registryAccess(), inventoryTag);
        this.inventory.selectedSlot = tag.getInt("SelectedItemSlot");
        if (tag.hasUUID(OWNER_NBT_KEY)) {
            this.ownerUuid = tag.getUUID(OWNER_NBT_KEY);
        }
        if (this.conversationLog != null && tag.contains(CONVERSATION_NBT_KEY)) {
            this.conversationLog.readNbt(
                    tag.getList(CONVERSATION_NBT_KEY, ConversationLog.nbtListType()));
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("head_yaw", this.yHeadRot);
        tag.put("Inventory", this.inventory.writeNbt(level().registryAccess(), new ListTag()));
        tag.putInt("SelectedItemSlot", this.inventory.selectedSlot);
        if (this.ownerUuid != null) {
            tag.putUUID(OWNER_NBT_KEY, this.ownerUuid);
        }
        if (this.conversationLog != null) {
            tag.put(CONVERSATION_NBT_KEY, this.conversationLog.writeNbt());
        }
    }

    @Override
    protected void dropAllDeathLoot(ServerLevel serverLevel, DamageSource damageSource) {
        super.dropAllDeathLoot(serverLevel, damageSource);
        inventory.dropAll();
    }

    /** Interpolated velocity for the renderer (elytra pose math). */
    public Vec3 lerpVelocity(float delta) {
        return this.lastVelocity.lerp(this.getDeltaMovement(), delta);
    }
}
