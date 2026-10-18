package com.teamuhi.superbenemies.entity.boss.common.ai

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.entity.boss.common.BossAircraft
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity

/**
 * Every Superb Warfare vehicle. The class is all a tracker can test (see [EntityTracker]); whether one is an airplane or a
 * helicopter is data-driven, so [isEnemyPlane] filters on that at query time.
 */
val TrackedVehicles = EntityTracker { it is VehicleEntity }

/**
 * Whether [entity] is an aircraft the bosses may hunt with `targetPriority = OTHER_PLANES` (experimental): an SBW airplane
 * or helicopter other than [boss] itself, in the same level, not a wreck and not a crippled boss, that is manned. Another
 * boss counts as manned (it has a hidden crew); any other plane needs a passenger who is not a creative / spectator
 * player, so parked empty planes are ignored. Range is not checked here.
 */
fun isEnemyPlane(boss: Entity, entity: Entity): Boolean {
    if (entity !is VehicleEntity || entity === boss) return false
    if (!entity.isAlive || entity.isRemoved || entity.level() !== boss.level()) return false
    val type = entity.vehicleType
    if (type != VehicleType.AIRPLANE && type != VehicleType.HELICOPTER) return false
    if (entity.isWreck) return false
    if (entity is BossAircraft) return !entity.crippled
    return entity.passengers.any { !(it is ServerPlayer && (it.isCreative || it.isSpectator)) }
}

/**
 * True when [entity] itself or one of its passengers has the string UUID [uuid]. A boss's last attacker is the pilot, not
 * the plane, so the "attacker first" rule needs this to find the plane the attacker is flying. Null never matches.
 */
fun riddenBy(entity: Entity, uuid: String?): Boolean {
    if (uuid.isNullOrEmpty()) return false
    return entity.stringUUID == uuid || entity.passengers.any { it.stringUUID == uuid }
}
