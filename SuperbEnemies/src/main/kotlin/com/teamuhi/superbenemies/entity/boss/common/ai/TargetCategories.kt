package com.teamuhi.superbenemies.entity.boss.common.ai

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.config.TargetCategory
import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Enemy

/**
 * Which [TargetCategory] [entity] falls into, or null when it is not a target at all. It only classifies: range, line of
 * sight, game mode, crew and the like are still checked by the caller.
 *
 * The dummy test comes before the mob test because the dummies are [Mob]s; a dummy whose `bossesTarget...` option is off
 * falls through to the mob rules (a dummy is not an [Enemy], so it then counts as a plain MOB).
 */
fun categoryOf(entity: Entity): TargetCategory? = when {
    entity is ServerPlayer -> TargetCategory.PLAYER
    entity is HumanoidDummyEntity && entity.settings.targetedByBosses() -> TargetCategory.PLAYER
    entity is VehicleEntity -> TargetCategory.PLANE
    entity is Mob -> if (entity is Enemy) TargetCategory.HOSTILE_MOB else TargetCategory.MOB
    else -> null
}
