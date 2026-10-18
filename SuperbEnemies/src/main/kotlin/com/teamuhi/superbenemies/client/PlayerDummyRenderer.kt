package com.teamuhi.superbenemies.client

import com.teamuhi.superbenemies.entity.dummy.PlayerDummyEntity
import net.minecraft.client.renderer.entity.EntityRendererProvider

/** Draws the player dummy with the vanilla player model and the default Steve or Alex skin (see [HumanoidDummyRenderer]). */
class PlayerDummyRenderer(context: EntityRendererProvider.Context) : HumanoidDummyRenderer<PlayerDummyEntity>(context)
