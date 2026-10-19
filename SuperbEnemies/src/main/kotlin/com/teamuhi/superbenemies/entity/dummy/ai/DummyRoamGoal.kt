package com.teamuhi.superbenemies.entity.dummy.ai

import com.teamuhi.superbenemies.config.DummyMovement
import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.level.pathfinder.Path
import java.util.EnumSet
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The dummy's ROAM movement: it picks a random spot near where it stands, pathfinds there, pauses, and repeats, so it
 * wanders around like a player looking for something.
 *
 * Runs in ROAM mode, except for [HumanoidDummyEntity.forcedStrafeUntil] ticks after the hide goal found no cover (the
 * strafe goal takes over then, so the dummy is never a sitting duck). Phases:
 *  - PAUSE: stands still for a random 0..`roamPauseSeconds` seconds, then picks the next spot.
 *  - WALK: follows the path to the spot and, on some legs, sprints (`sprinting`). It never hops at random: the only jumps
 *    are vanilla navigation's, to climb a block or step up on the path. A leg ends when the path is done, when the dummy has hardly moved for [STUCK_TICKS] ticks, or after [LEG_TIMEOUT_TICKS].
 *
 * A spot is a random point 3..`roamDistance` blocks from the dummy, resolved to a dry, standable block close to the
 * dummy's height (so it works indoors and on slopes), and accepted only when it is loaded, inside the leash and
 * reachable by path. The leash keeps the dummy within [ROAM_LEASH] blocks of its spawn point: beyond it only spots
 * towards the anchor are tried. Without an anchor (or an unloaded anchor chunk) there is no leash bias at all.
 *
 * Config options are read live, and all state is per instance. The look goal turns only the head while a path is in
 * progress, so the body faces the walking direction. While [HumanoidDummyEntity.roaming] is set, the look goal makes the
 * head trace the path ahead instead of staring at the threat, and glance around during pauses.
 */
class DummyRoamGoal(private val dummy: HumanoidDummyEntity) : Goal() {

    private enum class Phase { PAUSE, WALK }

    private var phase = Phase.PAUSE
    private var pauseUntil = 0

    private var legDeadline = 0
    private var sprintLeg = false
    private var stuckCheckTick = 0
    private var stuckX = 0.0
    private var stuckY = 0.0
    private var stuckZ = 0.0

    init {
        flags = EnumSet.of(Flag.MOVE, Flag.JUMP)
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun canUse(): Boolean {
        if (dummy.isPassenger || !dummy.isAlive) return false
        return dummy.settings.movement() == DummyMovement.ROAM && dummy.tickCount >= dummy.forcedStrafeUntil
    }

    override fun canContinueToUse(): Boolean = canUse()

    override fun start() {
        phase = Phase.PAUSE
        pauseUntil = dummy.tickCount // first spot straight away
        sprintLeg = false
        dummy.roaming = true
    }

    override fun stop() {
        dummy.roaming = false
        dummy.navigation.stop()
        dummy.isSprinting = false
        dummy.setCrouched(false)
        sprintLeg = false
    }

    /** The last [findPath] stopped for lack of path budget, not for lack of a spot: retry next tick, do not pause. */
    private var deferred = false

    override fun tick() {
        when (phase) {
            Phase.PAUSE -> tickPause()
            Phase.WALK -> tickWalk()
        }
    }

    // ================================================================================================== phases

    private fun tickPause() {
        val now = dummy.tickCount
        // The pause option was lowered while waiting: do not sit out the old, longer wait.
        pauseUntil = minOf(pauseUntil, now + dummy.settings.roamPauseSeconds() * 20)
        if (now < pauseUntil) return
        deferred = false
        if (!startLeg() && !deferred) enterPause(NO_SPOT_PAUSE_TICKS)
    }

    private fun tickWalk() {
        val now = dummy.tickCount

        // Options toggled mid-leg: a sprint must stop at once when sprinting is switched off. The flag also drops while
        // the dummy is barely moving (blocked, turning a corner), so no sprint particles puff out of a slow walk.
        val velocity = dummy.deltaMovement
        val sprint = sprintLeg && dummy.settings.sprinting() && hypot(velocity.x, velocity.z) > SPRINT_MIN_SPEED
        if (dummy.isSprinting != sprint) dummy.isSprinting = sprint

        if (dummy.navigation.isDone || now > legDeadline) {
            endLeg()
            return
        }
        if (now >= stuckCheckTick) {
            val moved = dummy.distanceToSqr(stuckX, stuckY, stuckZ)
            if (moved < STUCK_DISTANCE * STUCK_DISTANCE) {
                endLeg()
                return
            }
            rememberPosition()
        }
    }

    /** Picks a spot and starts walking to it. False when no reachable spot was found. */
    private fun startLeg(): Boolean {
        val path = findPath() ?: return false
        if (!dummy.navigation.moveTo(path, 1.0)) return false
        phase = Phase.WALK
        legDeadline = dummy.tickCount + LEG_TIMEOUT_TICKS
        sprintLeg = dummy.settings.sprinting() && dummy.getRandom().nextInt(100) < SPRINT_LEG_PERCENT
        rememberPosition()
        return true
    }

    private fun endLeg() {
        dummy.navigation.stop()
        dummy.isSprinting = false
        sprintLeg = false
        enterPause(dummy.getRandom().nextInt(dummy.settings.roamPauseSeconds() * 20 + 1))
    }

    private fun enterPause(ticks: Int) {
        phase = Phase.PAUSE
        pauseUntil = dummy.tickCount + ticks
    }

    private fun rememberPosition() {
        stuckCheckTick = dummy.tickCount + STUCK_TICKS
        stuckX = dummy.x
        stuckY = dummy.y
        stuckZ = dummy.z
    }

    // ================================================================================================== spot search

    /** The path to a random reachable spot, or null after [SPOT_TRIES] failed candidates. */
    private fun findPath(): Path? {
        val rng = dummy.getRandom()
        val maxDistance = dummy.settings.roamDistance().toDouble()
        val anchor = dummy.anchorCenter()
        val anchorDistance = anchor?.let { hypot(it.x - dummy.x, it.z - dummy.z) }
        // Outside the leash: only spots towards the anchor, within 60 degrees of straight at it.
        val homeAngle = if (anchor != null && anchorDistance != null && anchorDistance > ROAM_LEASH) {
            atan2(anchor.z - dummy.z, anchor.x - dummy.x)
        } else {
            null
        }

        repeat(SPOT_TRIES) {
            val angle = homeAngle?.let { it + (rng.nextDouble() * 2.0 - 1.0) * HOME_CONE_RADIANS }
                ?: (rng.nextDouble() * Math.PI * 2.0)
            val distance = MIN_SPOT_DISTANCE + rng.nextDouble() * (maxDistance - MIN_SPOT_DISTANCE).coerceAtLeast(0.0)
            val x = floor(dummy.x + cos(angle) * distance).toInt()
            val z = floor(dummy.z + sin(angle) * distance).toInt()
            if (anchor != null && hypot(x + 0.5 - anchor.x, z + 0.5 - anchor.z) > ROAM_LEASH) return@repeat
            val spot = standingSpot(x, z) ?: return@repeat
            // Out of path searches for this tick (other dummies used them): try the whole search again next tick.
            if (!DummyAi.pathfinds.tryTake(dummy.level())) {
                deferred = true
                return null
            }
            val path = dummy.navigation.createPath(spot, 1)
            if (path != null && path.canReach()) return path
        }
        return null
    }

    /**
     * The nearest-to-the-dummy's-height block in column ([x], [z]) with a solid, dry floor under it and two free, dry
     * blocks for the body, trying heights from [Y_OFFSETS] (up to 4 above and 6 below the dummy). Null in an unloaded chunk or when
     * the column has no such block.
     */
    private fun standingSpot(x: Int, z: Int): BlockPos? {
        val level = dummy.level()
        val cursor = BlockPos.MutableBlockPos()
        if (!level.isLoaded(cursor.set(x, dummy.blockPosition().y, z))) return null
        val base = dummy.blockPosition().y
        // Closest to the dummy's own height first: indoors that is the floor it stands on, not the roof above.
        for (offset in Y_OFFSETS) {
            val y = base + offset
            if (y - 1 < level.minBuildHeight || y + 1 >= level.maxBuildHeight) continue
            val floor = level.getBlockState(cursor.set(x, y - 1, z))
            if (floor.getCollisionShape(level, cursor).isEmpty || !floor.fluidState.isEmpty) continue
            val feet = level.getBlockState(cursor.set(x, y, z))
            if (!feet.getCollisionShape(level, cursor).isEmpty || !feet.fluidState.isEmpty) continue
            val head = level.getBlockState(cursor.set(x, y + 1, z))
            if (!head.getCollisionShape(level, cursor).isEmpty || !head.fluidState.isEmpty) continue
            return BlockPos(x, y, z)
        }
        return null
    }

    private companion object {
        /** Candidates tried per pick before the dummy gives up and waits a moment. */
        const val SPOT_TRIES = 10
        const val MIN_SPOT_DISTANCE = 3.0

        /** Keep this far (blocks) from the anchor; under the entity's own 64 block re-anchor limit. */
        const val ROAM_LEASH = 48.0
        const val HOME_CONE_RADIANS = Math.PI / 3.0

        /** Floor heights tried around the dummy's own, nearest first (up to 4 above and 6 below). */
        val Y_OFFSETS = intArrayOf(0, 1, -1, 2, -2, 3, -3, 4, -4, -5, -6)

        const val NO_SPOT_PAUSE_TICKS = 20

        /** Share (percent) of legs that are sprinted when `sprinting` is on. */
        const val SPRINT_LEG_PERCENT = 30

        /** Horizontal speed (blocks/tick) below which a sprint leg is not shown as sprinting; walking is about 0.2. */
        const val SPRINT_MIN_SPEED = 0.1

        const val LEG_TIMEOUT_TICKS = 400
        const val STUCK_TICKS = 40
        const val STUCK_DISTANCE = 0.5
    }
}
