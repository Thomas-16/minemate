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
import io.github.thomas16.minemate.integration.CompanionDefensePolicy;
import io.github.thomas16.minemate.integration.CompanionReflexTelemetry;
import io.github.thomas16.minemate.integration.ReflexSnapshot;
import io.github.thomas16.minemate.task.Reasons;
import io.github.thomas16.minemate.task.TaskSupervisor;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * The AI companion body: a LivingEntity that PlayerEngine can control.
 * No vanilla mob AI — all movement comes from the PlayerEngine/Automatone
 * pathfinder attached to this entity.
 */
public class CompanionEntity extends LivingEntity
        implements IAutomatone, IInventoryProvider, IInteractionManagerProvider, IHungerManagerProvider {

    private static final String OWNER_NBT_KEY = "minemate_owner";

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

    public Optional<UUID> getOwnerUuid() {
        return Optional.ofNullable(ownerUuid);
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
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
        }
        super.tick();
        this.updateSwingTime();
        this.hungerManager.update(this);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.level().isClientSide && this.controller != null) {
            this.supervisor.shutdown(Reasons.COMPANION_REMOVED);
            this.controller.stop();
        }
        super.remove(reason);
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
                if (this.inventory.insertStack(stack)) {
                    int collected = countBefore - stack.getCount();
                    if (collected <= 0) {
                        continue;
                    }
                    this.take(itemEntity, collected);
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
