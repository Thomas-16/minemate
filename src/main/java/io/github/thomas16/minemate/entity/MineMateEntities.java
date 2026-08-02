package io.github.thomas16.minemate.entity;

import io.github.thomas16.minemate.MineMate;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Zombie;

public final class MineMateEntities {
    public static final EntityType<CompanionEntity> COMPANION = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            MineMate.id("companion"),
            EntityType.Builder.of(CompanionEntity::new, MobCategory.CREATURE)
                    .sized(EntityType.PLAYER.getWidth(), EntityType.PLAYER.getHeight())
                    .clientTrackingRange(64)
                    .updateInterval(1)
                    .build(MineMate.id("companion").toString()));

    private MineMateEntities() {
    }

    public static void register() {
        // Player-like baseline stats; the companion has no vanilla mob AI,
        // it only needs the attribute set to be a valid LivingEntity.
        FabricDefaultAttributeRegistry.register(COMPANION, Zombie.createAttributes()
                .add(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED, 0.25)
                .add(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE, 1.0));
    }
}
