package com.teamuhi.superbenemies.entity.dummy.ai

import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The dummy runs for cover whenever [HumanoidDummyEntity.wantsCover] is true, in any movement mode. For the player dummy
 * that is: `hideFromBosses` is on and a live, uncrippled boss is within [DummyAi.BOSS_RANGE] blocks. The dummy soldier
 * also wants cover for a while after a hit, or when it is out of missiles.
 *
 * Cover is a standable spot with a roof: the height map's top blocking block (leaves count) is at least two blocks
 * above the dummy's head. The search is sampled, not exhaustive, and only runs when a boss shows up or the current
 * cover is lost (never every tick):
 *  - [SAMPLES] random columns within [SEARCH_RADIUS] blocks, each resolved to the first standable spot near the dummy's height,
 *  - ranked by distance minus a bonus for thick cover (solid blocks within [THICKNESS_SCAN] above),
 *  - tried best first: a candidate with no path, or that was not reached in time, falls through to the next.
 * Chunks are read with `getChunkNow`, so the search never loads or generates terrain.
 *
 * Phases: SEEK (sprint to the chosen spot) -> COVER (stand still, crouch now and then, the look goal keeps the boss in
 * sight; searches again if the roof is blown away) -> RETURN (once cover was not wanted for [HumanoidDummyEntity.coverMemoryTicks] ticks,
 * walk back to the spawn point and hand over to the movement mode). With no cover at all the dummy falls back to
 * strafing for a while ([HumanoidDummyEntity.forcedStrafeUntil]), so it is never a sitting duck.
 */
class DummyHideGoal(private val dummy: HumanoidDummyEntity) : Goal() {

    private enum class Phase { SEEK, COVER, RETURN }

    private var phase = Phase.SEEK
    private var finished = false

    private var cover: BlockPos? = null
    private val candidates = ArrayDeque<BlockPos>()
    private var needsSearch = true
    private var moveDeadline = 0

    private var lastThreatTick = 0
    private var nextScanTick = 0
    private var crouchTicks = 0
    private var returnDeadline = 0

    init {
        flags = EnumSet.of(Flag.MOVE, Flag.JUMP)
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun canUse(): Boolean {
        if (!dummy.coverEnabled() || dummy.isPassenger || !dummy.isAlive) return false
        val now = dummy.tickCount
        if (now < dummy.hideRetryUntil || now < nextScanTick) return false
        nextScanTick = now + scanInterval()
        return dummy.wantsCover()
    }

    override fun canContinueToUse(): Boolean =
        !finished && dummy.coverEnabled() && !dummy.isPassenger && dummy.isAlive

    override fun start() {
        val now = dummy.tickCount
        phase = Phase.SEEK
        finished = false
        cover = null
        candidates.clear()
        needsSearch = true
        lastThreatTick = now
        nextScanTick = now + scanInterval()
        crouchTicks = 0
    }

    override fun stop() {
        dummy.navigation.stop()
        dummy.isSprinting = false
        dummy.setCrouched(false)
        cover = null
        candidates.clear()
    }

    override fun tick() {
        val now = dummy.tickCount

        if (now >= nextScanTick) {
            nextScanTick = now + scanInterval()
            if (dummy.wantsCover()) {
                lastThreatTick = now
                if (phase == Phase.RETURN) enterSeek()
            }
        }
        if (phase != Phase.RETURN && now - lastThreatTick > dummy.coverMemoryTicks) enterReturn()

        when (phase) {
            Phase.SEEK -> tickSeek()
            Phase.COVER -> tickCover()
            Phase.RETURN -> tickReturn()
        }
    }

    /**
     * How often [HumanoidDummyEntity.wantsCover] is asked. A dummy with no memory (the soldier: it must walk back the
     * moment its cover time ends) asks every tick, which is cheap for it; the player dummy scans the bosses only every
     * [SCAN_INTERVAL] ticks.
     */
    private fun scanInterval(): Int = if (dummy.coverMemoryTicks <= 0) 1 else SCAN_INTERVAL

    // ================================================================================================== phases

    private fun enterSeek() {
        phase = Phase.SEEK
        cover = null
        candidates.clear()
        needsSearch = true
        dummy.setCrouched(false)
        crouchTicks = 0
    }

    private fun enterReturn() {
        phase = Phase.RETURN
        dummy.navigation.stop()
        dummy.isSprinting = false
        dummy.setCrouched(false)
        crouchTicks = 0
        returnDeadline = dummy.tickCount + RETURN_TIMEOUT_TICKS
    }

    private fun tickSeek() {
        val target = cover ?: nextCover()
        if (target == null) {
            // Nothing within reach is roofed: strafe instead of standing in the open, and try again later.
            dummy.hideRetryUntil = dummy.tickCount + NO_COVER_RETRY_TICKS
            dummy.forcedStrafeUntil = dummy.tickCount + NO_COVER_RETRY_TICKS
            finished = true
            return
        }
        cover = target

        val dx = target.x + 0.5 - dummy.x
        val dz = target.z + 0.5 - dummy.z
        if (hypot(dx, dz) < ARRIVE_DISTANCE && abs(target.y - dummy.y) < ARRIVE_HEIGHT) {
            phase = Phase.COVER
            dummy.navigation.stop()
            dummy.isSprinting = false
            return
        }

        dummy.isSprinting = true
        if (!dummy.navigation.isInProgress) {
            // moveTo returns false when there is no path; the next tick then picks the next candidate.
            if (!dummy.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 1.0)) {
                cover = null
            } else {
                moveDeadline = dummy.tickCount + MOVE_TIMEOUT_TICKS
            }
        } else if (dummy.tickCount > moveDeadline) {
            cover = null // too slow (stuck on something): give up on this spot
            dummy.navigation.stop()
        }
        if (cover != null && dummy.navigation.isDone && hypot(dx, dz) >= ARRIVE_DISTANCE) cover = null // path ended short
    }

    private fun tickCover() {
        val here = cover ?: return enterSeek()
        dummy.navigation.stop()

        // Pushed out of the spot (knockback): walk back into it.
        if (hypot(here.x + 0.5 - dummy.x, here.z + 0.5 - dummy.z) > DRIFT_DISTANCE) {
            phase = Phase.SEEK
            return
        }
        // The roof is gone (blown up): look for new cover.
        if (dummy.tickCount % COVER_CHECK_INTERVAL == 0 && !isCovered(dummy.blockPosition())) {
            enterSeek()
            return
        }

        if (crouchTicks > 0) {
            if (--crouchTicks == 0) dummy.setCrouched(false)
        } else if (dummy.getRandom().nextInt(COVER_CROUCH_CHANCE) == 0) {
            crouchTicks = COVER_CROUCH_MIN_TICKS + dummy.getRandom().nextInt(COVER_CROUCH_EXTRA_TICKS)
            dummy.setCrouched(true)
        }
    }

    private fun tickReturn() {
        val home = dummy.anchorCenter()
        if (home == null || dummy.tickCount > returnDeadline ||
            hypot(home.x - dummy.x, home.z - dummy.z) < HOME_DISTANCE
        ) {
            finished = true
            return
        }
        if (dummy.navigation.isDone && !dummy.navigation.moveTo(home.x, home.y, home.z, 1.0)) finished = true
    }

    // ================================================================================================== cover search

    /** The next untried cover spot; runs the search first when the list is empty and a search is due. */
    private fun nextCover(): BlockPos? {
        if (candidates.isEmpty() && needsSearch) {
            needsSearch = false
            val here = dummy.blockPosition()
            // Already under a roof when the boss appears: no need to go anywhere.
            if (isCovered(here)) return here
            search()
        }
        return candidates.removeFirstOrNull()
    }

    private fun search() {
        candidates.clear()
        val level = dummy.level()
        val rng = dummy.getRandom()
        val origin = dummy.blockPosition()
        val cursor = BlockPos.MutableBlockPos()
        val found = ArrayList<Pair<BlockPos, Double>>()

        repeat(SAMPLES) {
            val x = origin.x + rng.nextInt(2 * SEARCH_RADIUS + 1) - SEARCH_RADIUS
            val z = origin.z + rng.nextInt(2 * SEARCH_RADIUS + 1) - SEARCH_RADIUS
            val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return@repeat
            val y = standingY(chunk, cursor, x, origin.y, z) ?: return@repeat
            if (!roofed(chunk, x, y, z)) return@repeat
            val dx = (x - origin.x).toDouble()
            val dy = (y - origin.y).toDouble()
            val dz = (z - origin.z).toDouble()
            val score = hypot(hypot(dx, dy), dz) - thickness(chunk, cursor, x, y, z) * THICKNESS_BONUS
            found.add(Pair(BlockPos(x, y, z), score))
        }
        found.sortBy { it.second }
        for (i in 0 until minOf(MAX_CANDIDATES, found.size)) candidates.addLast(found[i].first)
    }

    /** True when [pos] is a spot the dummy could stand on that has a roof (read-only, never loads chunks). */
    private fun isCovered(pos: BlockPos): Boolean {
        val chunk = dummy.level().chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) ?: return false
        return roofed(chunk, pos.x, pos.y, pos.z)
    }

    /** The roof test: the top motion-blocking block of the column is at least two blocks above the dummy's head. */
    private fun roofed(chunk: LevelChunk, x: Int, y: Int, z: Int): Boolean =
        chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x and 15, z and 15) >= y + ROOF_MIN_OFFSET

    /**
     * The y of the first spot in column ([x], [z]) near [aroundY] with a solid, dry floor and two free blocks above it,
     * scanning down from [SEARCH_UP] blocks above [aroundY] to [SEARCH_DOWN] below. Null when there is none.
     */
    private fun standingY(chunk: LevelChunk, cursor: BlockPos.MutableBlockPos, x: Int, aroundY: Int, z: Int): Int? {
        val level = dummy.level()
        val top = minOf(aroundY + SEARCH_UP, level.maxBuildHeight - 3)
        val bottom = maxOf(aroundY - SEARCH_DOWN, level.minBuildHeight + 1)
        for (y in top downTo bottom) {
            val floor = chunk.getBlockState(cursor.set(x, y - 1, z))
            if (floor.getCollisionShape(chunk, cursor).isEmpty || !floor.fluidState.isEmpty) continue
            val feet = chunk.getBlockState(cursor.set(x, y, z))
            if (!feet.getCollisionShape(chunk, cursor).isEmpty || !feet.fluidState.isEmpty) return null
            val head = chunk.getBlockState(cursor.set(x, y + 1, z))
            if (!head.getCollisionShape(chunk, cursor).isEmpty || !head.fluidState.isEmpty) return null
            return y
        }
        return null
    }

    /** Solid blocks in the [THICKNESS_SCAN] blocks above the dummy's head: how well the roof holds up against shells. */
    private fun thickness(chunk: LevelChunk, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): Int {
        var solid = 0
        val top = minOf(y + 2 + THICKNESS_SCAN, dummy.level().maxBuildHeight - 1)
        for (above in y + 2..top) {
            if (!chunk.getBlockState(cursor.set(x, above, z)).getCollisionShape(chunk, cursor).isEmpty) solid++
        }
        return solid
    }

    private companion object {
        /** A boss within range is looked for this often (ticks). */
        const val SCAN_INTERVAL = 20

        const val SAMPLES = 200
        const val SEARCH_RADIUS = 24
        const val SEARCH_UP = 4
        const val SEARCH_DOWN = 6
        const val MAX_CANDIDATES = 12
        const val THICKNESS_SCAN = 8
        const val THICKNESS_BONUS = 0.75

        /** The heightmap value is the y above the top blocking block, so a roof two blocks over the head gives y + 3. */
        const val ROOF_MIN_OFFSET = 3

        const val ARRIVE_DISTANCE = 1.2
        const val ARRIVE_HEIGHT = 1.5
        const val DRIFT_DISTANCE = 2.5
        const val MOVE_TIMEOUT_TICKS = 200
        const val NO_COVER_RETRY_TICKS = 100

        const val COVER_CHECK_INTERVAL = 20
        const val COVER_CROUCH_CHANCE = 60
        const val COVER_CROUCH_MIN_TICKS = 20
        const val COVER_CROUCH_EXTRA_TICKS = 41

        const val HOME_DISTANCE = 1.5
        const val RETURN_TIMEOUT_TICKS = 400
    }
}
