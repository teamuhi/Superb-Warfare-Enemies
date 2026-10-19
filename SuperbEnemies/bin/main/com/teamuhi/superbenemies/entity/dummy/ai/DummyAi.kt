package com.teamuhi.superbenemies.entity.dummy.ai

import com.teamuhi.superbenemies.entity.boss.common.BossAircraft
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedBosses
import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import kotlin.math.cos
import kotlin.math.sin

/**
 * Caps how many expensive operations (pathfinds, long raycasts) all dummies together may start in one server tick.
 * Each dummy times its work on its own clock, so with many of them the same tick can collect all of it; a dummy that
 * is refused simply tries again on the next tick. Server thread only.
 */
internal class TickBudget(private val perTick: Int) {
    private var tick = Long.MIN_VALUE
    private var left = 0

    /** True (and one unit spent) while the budget for the level's current game tick has not run out. */
    fun tryTake(level: Level): Boolean {
        val now = level.gameTime
        if (now != tick) {
            tick = now
            left = perTick
        }
        if (left <= 0) return false
        left--
        return true
    }
}

/** Small helpers shared by the dummy's goals. */
internal object DummyAi {

    /** Path searches (`createPath` / `moveTo`) all dummies may start per tick. */
    val pathfinds = TickBudget(3)

    /** Idle-scan line-of-sight raycasts all soldiers may start per tick. */
    val sightRays = TickBudget(4)

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
