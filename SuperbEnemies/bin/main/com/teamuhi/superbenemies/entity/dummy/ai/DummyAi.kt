package com.teamuhi.superbenemies.entity.dummy.ai

import com.teamuhi.superbenemies.entity.boss.common.BossAircraft
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedBosses
import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import net.minecraft.world.entity.Entity
import kotlin.math.cos
import kotlin.math.sin

/** Small helpers shared by the dummy's goals. */
internal object DummyAi {

    /** A boss within this many blocks makes the dummy look at it and (with `hideFromBosses` for the player dummy) run for cover. */
    const val BOSS_RANGE = 400.0

    /**
     * Component of the world-space horizontal vector ([dx], [dz]) along the facing direction of a mob with yaw
     * [yawDeg]. Minecraft: yaw 0 faces +z, and a positive left input moves towards +x at yaw 0.
     */
    fun localForward(yawDeg: Float, dx: Double, dz: Double): Float {
        val yaw = Math.toRadians(yawDeg.toDouble())
        return (-dx * sin(yaw) + dz * cos(yaw)).toFloat()
    }

    /** Component of ([dx], [dz]) along the mob's left side (see [localForward]). */
    fun localLeft(yawDeg: Float, dx: Double, dz: Double): Float {
        val yaw = Math.toRadians(yawDeg.toDouble())
        return (dx * cos(yaw) + dz * sin(yaw)).toFloat()
    }

    /** The nearest live boss aircraft within [BOSS_RANGE] blocks of [dummy] (any state), or null. */
    fun nearestBoss(dummy: HumanoidDummyEntity, includeCrippled: Boolean): Entity? {
        var best: Entity? = null
        var bestDistance = BOSS_RANGE * BOSS_RANGE
        for (entity in TrackedBosses.near(dummy.level(), dummy.position(), BOSS_RANGE)) {
            val boss = entity as? BossAircraft ?: continue
            if (!includeCrippled && boss.crippled) continue
            val distance = entity.distanceToSqr(dummy)
            if (distance < bestDistance) {
                bestDistance = distance
                best = entity
            }
        }
        return best
    }
}
