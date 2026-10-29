package com.teamuhi.superbenemies.entity.boss.common.ai

import com.teamuhi.superbenemies.config.TargetingMethod
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.phys.Vec3

/**
 * Turns "the entity the boss chose" into "what its weapons should aim at", for the `DYNAMIC` targeting method. One per
 * boss brain.
 *
 * While the chosen entity is exposed ([SkyExposure.isExposed]) it is returned as is and its position is remembered. Once
 * a cover block closes over it, [resolve] returns a ghost standing at that last known position instead, so the boss
 * keeps attacking the spot where it lost sight, not the hidden target.
 *
 * Why a Marker stand-in: both brains and the AC-130 gunnery are written against `aim: Entity` (bounding box centre, id,
 * `rootVehicle`, `isAlive`). A never-spawned [EntityType.MARKER] gives "attack the last known position" without touching
 * any attack or flight code. Its id is new, so the brains' velocity estimate resets to zero, which is right for a fixed
 * spot. The marker has zero size, so its bounding box centre is its position (the feet of the last seen spot). The ghost
 * is never added to the world, so nothing ticks, saves or syncs it.
 *
 * The exposure check is cached per target and repeated every [CHECK_INTERVAL] ticks (staggered by the boss id so bosses
 * do not all scan on the same tick); a new target is checked at once.
 */
class TargetSight(private val boss: Entity) {

    private var ghost: Entity? = null
    private var checkedId = -1
    private var exposed = true
    private var lastSeen: Vec3? = null

    /** True while the last [resolve] returned the ghost (the real target is hidden). */
    var hidden: Boolean = false
        private set

    /**
     * What to aim at for [aim]: [aim] itself for a null aim or the STATIC method, otherwise [aim] while exposed and the
     * ghost at the last known position while hidden. A target that is hidden the first time it is looked at counts as
     * last seen where it is now: the boss "saw" it when it chose it.
     */
    fun resolve(aim: Entity?, method: TargetingMethod): Entity? {
        if (aim == null || method == TargetingMethod.STATIC) {
            reset()
            return aim
        }
        val changed = aim.id != checkedId
        if (changed) {
            // A different target: whatever was remembered belongs to the old one.
            checkedId = aim.id
            lastSeen = null
        }
        if (changed || (boss.tickCount + boss.id) % CHECK_INTERVAL == 0) {
            exposed = SkyExposure.isExposed(aim) || SkyExposure.hasLineOfSight(boss, aim)
        }

        if (exposed) {
            lastSeen = aim.position()
            hidden = false
            return aim
        }
        val seen = lastSeen ?: aim.position().also { lastSeen = it }
        hidden = true
        return ghostAt(seen)
    }

    /** True when [entity] is this brain's ghost (the real target is hidden), so it must not be treated as a real entity. */
    fun isGhost(entity: Entity?): Boolean = entity != null && entity === ghost

    /** Whether [entity] is hidden under `DYNAMIC` right now. Not cached, for scanning candidates. */
    fun isHidden(entity: Entity, method: TargetingMethod): Boolean =
        method == TargetingMethod.DYNAMIC && !SkyExposure.isExposed(entity) &&
            !SkyExposure.hasLineOfSight(boss, entity)

    /** Forgets the cached target and last known position (a new fight, or the STATIC method). */
    fun reset() {
        checkedId = -1
        exposed = true
        lastSeen = null
        hidden = false
    }

    /**
     * The reused ghost, moved to [pos]; recreated if the boss changed dimension. The previous position is set too: the
     * ghost is never ticked, so `xo/yo/zo` would otherwise stay at the origin and any `x - xo` velocity read (the A-10's
     * lead calculations) would see a huge bogus speed instead of a fixed spot.
     */
    private fun ghostAt(pos: Vec3): Entity? {
        val level = boss.level()
        var marker = ghost
        if (marker == null || marker.level() !== level) {
            marker = EntityType.MARKER.create(level)
            ghost = marker
        }
        marker?.let {
            it.setPos(pos.x, pos.y, pos.z)
            it.setOldPosAndRot()
        }
        return marker
    }

    private companion object {
        /** Ticks between exposure re-checks of the same target. */
        const val CHECK_INTERVAL = 5
    }
}
