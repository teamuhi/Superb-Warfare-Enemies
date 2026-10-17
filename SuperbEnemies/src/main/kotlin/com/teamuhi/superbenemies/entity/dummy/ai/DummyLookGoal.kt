package com.teamuhi.superbenemies.entity.dummy.ai

import com.teamuhi.superbenemies.entity.dummy.PlayerDummyEntity
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.player.Player
import java.util.EnumSet
import kotlin.math.hypot

/**
 * Turns the dummy to face the nearest threat the way a player would: the nearest boss within
 * [DummyAi.BOSS_RANGE] blocks, otherwise the nearest player within [PLAYER_RANGE]. Runs in every movement mode, so a
 * STATIC dummy still follows the action.
 *
 * Body, head and pitch are turned directly (vanilla's look control only moves the head and adds an offset). While the
 * dummy is following a path (running to cover) only the head and pitch turn, so it faces where it is running.
 * The threat is re-picked every [SCAN_INTERVAL] ticks; aiming happens every tick.
 */
class DummyLookGoal(private val dummy: PlayerDummyEntity) : Goal() {

    private var threat: Entity? = null
    private var nextScanTick = 0

    init {
        flags = EnumSet.of(Flag.LOOK)
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun canUse(): Boolean {
        if (dummy.isPassenger || !dummy.isAlive) return false
        rescanIfDue()
        return threat != null
    }

    override fun canContinueToUse(): Boolean = canUse()

    override fun stop() {
        threat = null
    }

    override fun tick() {
        rescanIfDue()
        val target = threat ?: return

        // Players are looked at in the face, a (large) aircraft at its centre.
        val aimY = if (target is Player) target.eyeY else target.y + target.bbHeight * 0.5
        val dx = target.x - dummy.x
        val dz = target.z - dummy.z
        val horizontal = hypot(dx, dz)

        if (horizontal > 1.0e-3) {
            val wantedYaw = (Mth.atan2(dz, dx) * RAD_TO_DEG).toFloat() - 90f
            val yaw = Mth.approachDegrees(dummy.yRot, wantedYaw, MAX_TURN_PER_TICK)
            if (dummy.navigation.isInProgress) {
                dummy.yHeadRot = Mth.approachDegrees(dummy.yHeadRot, wantedYaw, MAX_TURN_PER_TICK)
            } else {
                dummy.yRot = yaw
                dummy.yBodyRot = yaw
                dummy.yHeadRot = yaw
            }
        }
        val wantedPitch = (-(Mth.atan2(aimY - dummy.eyeY, horizontal) * RAD_TO_DEG)).toFloat().coerceIn(-MAX_PITCH, MAX_PITCH)
        dummy.xRot = Mth.approachDegrees(dummy.xRot, wantedPitch, MAX_TURN_PER_TICK)
    }

    private fun rescanIfDue() {
        val now = dummy.tickCount
        if (now < nextScanTick) {
            // A threat that died or left the level since the last scan is dropped at once.
            threat?.let { if (!it.isAlive || it.level() !== dummy.level()) threat = null }
            return
        }
        nextScanTick = now + SCAN_INTERVAL
        threat = DummyAi.nearestBoss(dummy, includeCrippled = true)
            ?: dummy.level().getNearestPlayer(dummy, PLAYER_RANGE)
    }

    private companion object {
        const val SCAN_INTERVAL = 10
        const val PLAYER_RANGE = 32.0
        const val MAX_TURN_PER_TICK = 25f
        const val MAX_PITCH = 60f
        const val RAD_TO_DEG = 57.29577951308232
    }
}
