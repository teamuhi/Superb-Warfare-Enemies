package com.teamuhi.superbenemies.client

import com.mojang.blaze3d.vertex.PoseStack
import com.teamuhi.superbenemies.entity.dummy.PlayerDummyEntity
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3

/**
 * Draws the player dummy with the vanilla player model and the default Steve or Alex skin.
 *
 * Vanilla picks the default skin from the player's UUID, and so does this: [DefaultPlayerSkin] gives both the texture and
 * the arm width ("slim" = Alex), so the same dummy always looks the same on every client. The wide and the slim model are
 * both baked once and swapped per entity just before drawing (the layers read the renderer's current model, so held
 * items follow the right arm shape). Crouching copies the player renderer: the model's sneak pose plus a small drop.
 */
class PlayerDummyRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<PlayerDummyEntity, PlayerModel<PlayerDummyEntity>>(
        context, PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), SHADOW_RADIUS
    ) {

    private val wideModel: PlayerModel<PlayerDummyEntity> = model
    private val slimModel = PlayerModel<PlayerDummyEntity>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true)

    override fun render(
        entity: PlayerDummyEntity, entityYaw: Float, partialTick: Float,
        poseStack: PoseStack, buffer: MultiBufferSource, packedLight: Int
    ) {
        model = if (DefaultPlayerSkin.getSkinModelName(entity.uuid) == "slim") slimModel else wideModel
        // HumanoidModel only crouches when told to; the player renderer does the same in setModelProperties.
        model.crouching = entity.isCrouching
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight)
    }

    override fun getTextureLocation(entity: PlayerDummyEntity): ResourceLocation =
        DefaultPlayerSkin.getDefaultSkin(entity.uuid)

    /** Same 2 pixel drop the player renderer applies while sneaking. */
    override fun getRenderOffset(entity: PlayerDummyEntity, partialTick: Float): Vec3 =
        if (entity.isCrouching) CROUCH_OFFSET else super.getRenderOffset(entity, partialTick)

    private companion object {
        const val SHADOW_RADIUS = 0.5f
        val CROUCH_OFFSET = Vec3(0.0, -0.125, 0.0)
    }
}
