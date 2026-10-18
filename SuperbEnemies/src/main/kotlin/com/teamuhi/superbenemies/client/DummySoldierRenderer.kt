package com.teamuhi.superbenemies.client

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.entity.soldier.DummySoldierEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.model.HumanoidModel.ArmPose
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.HumanoidArm

/**
 * Draws the Dummy Soldier: the shared dummy renderer plus an optional custom skin and arm poses for the held Igla.
 *
 * A [net.minecraft.client.renderer.entity.HumanoidMobRenderer] never asks the held item for an arm pose (only the player
 * renderer does), so [prepareModel] sets them: both arms raised while the soldier aims down sights, the two-handed carry
 * while it sprints, otherwise the main arm holds the item. The Igla itself is drawn by Superb Warfare's item renderer.
 *
 * The skin `assets/superbenemies/textures/entity/dummy_soldier.png` is optional. Its presence is looked up once and cached
 * (a texture lookup per frame would hit the resource manager); [invalidateSkinCache] clears it on every resource reload.
 * Without the file the soldier uses the default Steve or Alex skin, like the player dummy.
 */
class DummySoldierRenderer(context: EntityRendererProvider.Context) : HumanoidDummyRenderer<DummySoldierEntity>(context) {

    override fun textureFor(entity: DummySoldierEntity): ResourceLocation =
        if (hasCustomSkin()) SKIN else super.textureFor(entity)

    /** A custom skin uses the wide (Steve) arm layout; the default skin keeps its UUID-based choice. */
    override fun usesSlimModel(entity: DummySoldierEntity): Boolean =
        !hasCustomSkin() && super.usesSlimModel(entity)

    override fun prepareModel(entity: DummySoldierEntity, model: PlayerModel<DummySoldierEntity>) {
        val main: ArmPose
        val off: ArmPose
        when {
            entity.aiming -> {
                main = ArmPose.BOW_AND_ARROW
                off = ArmPose.BOW_AND_ARROW
            }
            entity.isSprinting -> {
                main = ArmPose.CROSSBOW_CHARGE
                off = ArmPose.CROSSBOW_CHARGE
            }
            else -> {
                main = ArmPose.ITEM
                off = ArmPose.EMPTY
            }
        }
        if (entity.mainArm == HumanoidArm.LEFT) {
            model.leftArmPose = main
            model.rightArmPose = off
        } else {
            model.rightArmPose = main
            model.leftArmPose = off
        }
    }

    companion object {
        private val SKIN = ResourceLocation(SuperbEnemies.MOD_ID, "textures/entity/dummy_soldier.png")

        /** null = not looked up yet. Written by the render thread and the reload listener, hence volatile. */
        @Volatile
        private var customSkin: Boolean? = null

        private fun hasCustomSkin(): Boolean {
            customSkin?.let { return it }
            val present = Minecraft.getInstance().resourceManager.getResource(SKIN).isPresent
            customSkin = present
            return present
        }

        /** Forgets the cached lookup; called when resource packs reload so an added or removed skin is noticed. */
        fun invalidateSkinCache() {
            customSkin = null
        }
    }
}
