package io.github.thomas16.minemate.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import io.github.thomas16.minemate.entity.CompanionEntity;

import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.phys.Vec3;

/**
 * Renders the companion with the vanilla player model and the default
 * (Steve) skin. Custom skins can come later.
 */
public class CompanionRenderer extends LivingEntityRenderer<CompanionEntity, PlayerModel<CompanionEntity>> {

    public CompanionRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(ctx.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.getModelManager()));
        this.addLayer(new ItemInHandLayer<>(this, ctx.getItemInHandRenderer()));
        this.addLayer(new CustomHeadLayer<>(this, ctx.getModelSet(), ctx.getItemInHandRenderer()));
        this.addLayer(new ElytraLayer<>(this, ctx.getModelSet()));
    }

    @Override
    public ResourceLocation getTextureLocation(CompanionEntity entity) {
        return DefaultPlayerSkin.getDefaultTexture();
    }

    @Override
    public void render(CompanionEntity entity, float entityYaw, float partialTicks, PoseStack poseStack,
            net.minecraft.client.renderer.MultiBufferSource buffer, int packedLight) {
        setModelPose(entity);
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    private void setModelPose(CompanionEntity entity) {
        PlayerModel<CompanionEntity> model = this.getModel();
        model.setAllVisible(true);
        model.crouching = entity.isCrouching();

        HumanoidModel.ArmPose mainPose = getArmPose(entity, InteractionHand.MAIN_HAND);
        HumanoidModel.ArmPose offPose = getArmPose(entity, InteractionHand.OFF_HAND);
        if (mainPose.isTwoHanded()) {
            offPose = entity.getOffhandItem().isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        }
        if (entity.getMainArm() == HumanoidArm.RIGHT) {
            model.rightArmPose = mainPose;
            model.leftArmPose = offPose;
        } else {
            model.rightArmPose = offPose;
            model.leftArmPose = mainPose;
        }
    }

    private static HumanoidModel.ArmPose getArmPose(CompanionEntity entity, InteractionHand hand) {
        ItemStack stack = entity.getItemInHand(hand);
        if (stack.isEmpty()) {
            return HumanoidModel.ArmPose.EMPTY;
        }
        if (entity.getUsedItemHand() == hand && entity.getUseItemRemainingTicks() > 0) {
            UseAnim anim = stack.getUseAnimation();
            switch (anim) {
                case BLOCK: return HumanoidModel.ArmPose.BLOCK;
                case BOW: return HumanoidModel.ArmPose.BOW_AND_ARROW;
                case SPEAR: return HumanoidModel.ArmPose.THROW_SPEAR;
                case CROSSBOW: return HumanoidModel.ArmPose.CROSSBOW_CHARGE;
                case SPYGLASS: return HumanoidModel.ArmPose.SPYGLASS;
                case TOOT_HORN: return HumanoidModel.ArmPose.TOOT_HORN;
                case BRUSH: return HumanoidModel.ArmPose.BRUSH;
                default: return HumanoidModel.ArmPose.ITEM;
            }
        }
        if (!entity.swinging && stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack)) {
            return HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }
        return HumanoidModel.ArmPose.ITEM;
    }

    @Override
    protected void scale(CompanionEntity entity, PoseStack poseStack, float partialTicks) {
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
    }

    @Override
    protected void setupRotations(CompanionEntity entity, PoseStack poseStack, float bodyYaw, float yRot,
            float partialTicks, float scale) {
        float swimAmount = entity.getSwimAmount(partialTicks);
        if (entity.isFallFlying()) {
            super.setupRotations(entity, poseStack, bodyYaw, yRot, partialTicks, scale);
            float flyTicks = (float) entity.getFallFlyingTicks() + partialTicks;
            float pitchProgress = Mth.clamp(flyTicks * flyTicks / 100.0F, 0.0F, 1.0F);
            if (!entity.isAutoSpinAttack()) {
                poseStack.mulPose(Axis.XP.rotationDegrees(pitchProgress * (-90.0F - entity.getXRot())));
            }
            Vec3 view = entity.getViewVector(partialTicks);
            Vec3 velocity = entity.lerpVelocity(partialTicks);
            double velHorizontal = velocity.horizontalDistanceSqr();
            double viewHorizontal = view.horizontalDistanceSqr();
            if (velHorizontal > 0.0 && viewHorizontal > 0.0) {
                double dot = (velocity.x * view.x + velocity.z * view.z) / Math.sqrt(velHorizontal * viewHorizontal);
                double cross = velocity.x * view.z - velocity.z * view.x;
                poseStack.mulPose(Axis.YP.rotation((float) (Math.signum(cross) * Math.acos(dot))));
            }
        } else if (swimAmount > 0.0F) {
            super.setupRotations(entity, poseStack, bodyYaw, yRot, partialTicks, scale);
            float targetPitch = entity.isInWater() ? -90.0F - entity.getXRot() : -90.0F;
            poseStack.mulPose(Axis.XP.rotationDegrees(Mth.lerp(swimAmount, 0.0F, targetPitch)));
            if (entity.isVisuallySwimming()) {
                poseStack.translate(0.0F, -1.0F, 0.3F);
            }
        } else {
            super.setupRotations(entity, poseStack, bodyYaw, yRot, partialTicks, scale);
        }
    }
}
