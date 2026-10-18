package com.teamuhi.superbenemies.client

import com.mojang.blaze3d.vertex.PoseStack
import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3

/**
 * Draws a [HumanoidDummyEntity] with the vanilla player model. The default is the Steve or Alex skin vanilla would give a
 * player with the entity's UUID, so the same dummy always looks the same on every client; subclasses swap the texture
 * ([textureFor]), the arm width ([usesSlimModel]) or the arm poses ([prepareModel]).
 *
 * [DefaultPlayerSkin] gives both the texture and the arm width ("slim" = Alex). The wide and the slim model are both
 * baked once and swapped per entity just before drawing (the layers read the renderer's current model, so held items
 * follow the right arm shape). Crouching copies the player renderer: the model's sneak pose plus a small drop.
 */
open class HumanoidDummyRenderer<T : HumanoidDummyEntity>(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<T, PlayerModel<T>>(
        context, PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), SHADOW_RADIUS
    ) {

    private val wideModel: PlayerModel<T> = model
    private val slimModel = PlayerModel<T>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true)

    /** The skin of [entity]. */
    protected open fun textureFor(entity: T): ResourceLocation = DefaultPlayerSkin.getDefaultSkin(entity.uuid)

    /** True for the 3 pixel (Alex) arms, false for the 4 pixel (Steve) arms. */
    protected open fun usesSlimModel(entity: T): Boolean = DefaultPlayerSkin.getSkinModelName(entity.uuid) == "slim"

    /** Called right before drawing, with the model already chosen: set the arm poses (or anything else) here. */
    protected open fun prepareModel(entity: T, model: PlayerModel<T>) {}

    override fun render(
        entity: T, entityYaw: Float, partialTick: Float,
        poseStack: PoseStack, buffer: MultiBufferSource, packedLight: Int
    ) {
        model = if (usesSlimModel(entity)) slimModel else wideModel
        // HumanoidModel only crouches when told to; the player renderer does the same in setModelProperties.
        model.crouching = entity.isCrouching
        prepareModel(entity, model)
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight)
    }

    override fun getTextureLocation(entity: T): ResourceLocation = textureFor(entity)

    /** Same 2 pixel drop the player renderer applies while sneaking. */
    override fun getRenderOffset(entity: T, partialTick: Float): Vec3 =
        if (entity.isCrouching) CROUCH_OFFSET else super.getRenderOffset(entity, partialTick)

    private companion object {
        const val SHADOW_RADIUS = 0.5f
        val CROUCH_OFFSET = Vec3(0.0, -0.125, 0.0)
    }
}
